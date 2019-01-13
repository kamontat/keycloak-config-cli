package de.adorsys.keycloak.config.service;

import de.adorsys.keycloak.config.model.RealmImport;
import de.adorsys.keycloak.config.repository.RealmRepository;
import de.adorsys.keycloak.config.util.CloneUtils;
import org.keycloak.admin.client.resource.ClientResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.RoleResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class RealmImportService {
    private static final Logger logger = LoggerFactory.getLogger(RealmImportService.class);

    private final String[] ignoredPropertiesForCreation = new String[]{
            "users",
            "browserFlow",
            "directGrantFlow",
            "clientAuthenticationFlow",
            "registrationFlow",
            "resetCredentialsFlow",
            "components",
            "authenticationFlows"
    };

    private final String[] ignoredPropertiesForUpdate = new String[]{
            "clients",
            "roles",
            "users",
            "browserFlow",
            "directGrantFlow",
            "clientAuthenticationFlow",
            "registrationFlow",
            "resetCredentialsFlow",
            "components",
            "authenticationFlows",
            "requiredActions"
    };

    private final String[] patchingPropertiesForFlowImport = new String[]{
            "browserFlow",
            "directGrantFlow",
            "clientAuthenticationFlow",
            "registrationFlow",
            "resetCredentialsFlow",
    };

    private final KeycloakProvider keycloakProvider;
    private final RealmRepository realmRepository;

    private final UserImportService userImportService;
    private final RoleImportService roleImportService;
    private final ClientImportService clientImportService;
    private final ComponentImportService componentImportService;
    private final AuthenticationFlowsImportService authenticationFlowsImportService;
    private final RequiredActionsImportService requiredActionsImportService;

    @Autowired
    public RealmImportService(
            KeycloakProvider keycloakProvider,
            RealmRepository realmRepository,
            UserImportService userImportService,
            RoleImportService roleImportService,
            ClientImportService clientImportService,
            ComponentImportService componentImportService,
            AuthenticationFlowsImportService authenticationFlowsImportService,
            RequiredActionsImportService requiredActionsImportService
    ) {
        this.keycloakProvider = keycloakProvider;
        this.realmRepository = realmRepository;
        this.userImportService = userImportService;
        this.roleImportService = roleImportService;
        this.clientImportService = clientImportService;
        this.componentImportService = componentImportService;
        this.authenticationFlowsImportService = authenticationFlowsImportService;
        this.requiredActionsImportService = requiredActionsImportService;
    }

    public void doImport(RealmImport realmImport) {
        Optional<RealmResource> maybeRealm = realmRepository.tryToLoadRealm(realmImport.getRealm());

        if(maybeRealm.isPresent()) {
            updateRealm(realmImport);
        } else {
            createRealm(realmImport);
        }

        keycloakProvider.close();
    }

    private void createRealm(RealmImport realmImport) {
        if(logger.isDebugEnabled()) logger.debug("Creating realm '{}' ...", realmImport.getRealm());

        RealmRepresentation realmForCreation = CloneUtils.deepClone(realmImport, RealmRepresentation.class, ignoredPropertiesForCreation);
        keycloakProvider.get().realms().create(realmForCreation);

        realmRepository.loadRealm(realmImport.getRealm());
        importUsers(realmImport);
        importAuthenticationFlows(realmImport);
        importFlows(realmImport);
        importComponents(realmImport);
    }

    private void importComponents(RealmImport realmImport) {
        componentImportService.doImport(realmImport);
    }

    private void updateRealm(RealmImport realmImport) {
        if(logger.isDebugEnabled()) logger.debug("Updating realm '{}'...", realmImport.getRealm());

        RealmRepresentation realmToUpdate = CloneUtils.deepClone(realmImport, RealmRepresentation.class, ignoredPropertiesForUpdate);
        realmRepository.loadRealm(realmImport.getRealm()).update(realmToUpdate);

        setupImpersonation(realmImport);
        importClients(realmImport);
        importRoles(realmImport);
        importUsers(realmImport);
        importRequiredActions(realmImport);
        importAuthenticationFlows(realmImport);
        importFlows(realmImport);
        importComponents(realmImport);
    }

    private void importRequiredActions(RealmImport realmImport) {
        requiredActionsImportService.doImport(realmImport);
    }

    private void importAuthenticationFlows(RealmImport realmImport) {
        authenticationFlowsImportService.doImport(realmImport);
    }

    private void importUsers(RealmImport realmImport) {
        List<UserRepresentation> users = realmImport.getUsers();

        if(users != null) {
            for(UserRepresentation user : users) {
                userImportService.importUser(realmImport.getRealm(), user);
            }
        }
    }

    private void importRoles(RealmImport realmImport) {
        roleImportService.doImport(realmImport);
    }

    private void importClients(RealmImport realmImport) {
        clientImportService.doImport(realmImport);
    }

    private void importFlows(RealmImport realmImport) {
        RealmResource realmResource = realmRepository.loadRealm(realmImport.getRealm());
        RealmRepresentation existingRealm = realmResource.toRepresentation();

        RealmRepresentation realmToUpdate = CloneUtils.deepPatchFieldsOnly(existingRealm, realmImport, patchingPropertiesForFlowImport);
        realmResource.update(realmToUpdate);
    }

    private void setupImpersonation(RealmImport realmImport) {
        realmImport.getCustomImport().ifPresent(customImport -> {
            if(customImport.removeImpersonation()) {
                RealmResource master = keycloakProvider.get().realm("master");

                String clientId = realmImport.getRealm() + "-realm";
                List<ClientRepresentation> foundClients = master.clients()
                        .findByClientId(clientId);

                if(!foundClients.isEmpty()) {
                    ClientRepresentation client = foundClients.get(0);
                    ClientResource clientResource = master.clients()
                            .get(client.getId());

                    RoleResource impersonationRole = clientResource.roles().get("impersonation");

                    try {
                        impersonationRole.remove();
                    } catch(javax.ws.rs.NotFoundException e) {
                        if(logger.isInfoEnabled()) logger.info("Cannot remove 'impersonation' role from client '{}' in 'master' realm: Not found", clientId);
                    }
                }
            }
        });
    }
}
