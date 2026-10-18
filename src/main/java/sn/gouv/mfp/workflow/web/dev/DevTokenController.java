package sn.gouv.mfp.workflow.web.dev;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sn.gouv.mfp.workflow.web.dev.DevTokenService.DevToken;

/**
 * Générateur de jetons de DÉVELOPPEMENT (SEC-08), pour la console de test (Phase 2).
 * <p>
 * <strong>Profil {@code dev} uniquement</strong> : le contrôleur n'existe qu'avec
 * {@code SPRING_PROFILES_ACTIVE=dev}. En production — profil {@code prod} ou aucun profil —
 * il n'est pas monté, ces chemins ne trouvent aucun handler (404) et n'accordent donc
 * jamais de jeton. Choix volontairement plus strict que {@code @Profile("!prod")} : on
 * n'expose pas d'usine à jetons par simple omission du profil de production.
 */
@Tag(name = "Outils de développement",
        description = "Génération de jetons JWT de test (profil dev uniquement).")
@RestController
@RequestMapping("/api/dev")
@Profile("dev")
public class DevTokenController {

    private final DevTokenService tokens;

    public DevTokenController(DevTokenService tokens) {
        this.tokens = tokens;
    }

    @Operation(
            summary = "Lister un jeton par rôle de test",
            description = "Renvoie un JWT signé pour chaque rôle moteur déclaré par l'émetteur "
                    + "de développement. direction/bureau (facultatifs) alimentent le "
                    + "cloisonnement organisationnel (SEC-04).")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Jetons émis."),
        @ApiResponse(responseCode = "409", description = "Aucun émetteur de développement configuré.")})
    @GetMapping("/tokens")
    public List<DevToken> tokens(
            @Parameter(description = "Direction à porter dans le jeton (cloisonnement SEC-04).")
            @RequestParam(required = false) String direction,
            @Parameter(description = "Bureau à porter dans le jeton (cloisonnement SEC-04).")
            @RequestParam(required = false) String bureau) {
        return tokens.allTokens(direction, bureau);
    }

    @Operation(
            summary = "Jeton pour un rôle donné",
            description = "Renvoie un JWT signé pour le rôle moteur demandé (ex. CITOYEN, "
                    + "CHEF_DIVISION, AGENT_TRAITEMENT, DLEC, DGFP).")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Jeton émis."),
        @ApiResponse(responseCode = "400", description = "Rôle inconnu de l'émetteur de développement."),
        @ApiResponse(responseCode = "409", description = "Aucun émetteur de développement configuré.")})
    @GetMapping("/tokens/{role}")
    public DevToken token(
            @Parameter(description = "Rôle moteur.", example = "CITOYEN") @PathVariable String role,
            @Parameter(description = "Direction à porter dans le jeton (SEC-04).")
            @RequestParam(required = false) String direction,
            @Parameter(description = "Bureau à porter dans le jeton (SEC-04).")
            @RequestParam(required = false) String bureau) {
        return tokens.token(role, direction, bureau);
    }
}
