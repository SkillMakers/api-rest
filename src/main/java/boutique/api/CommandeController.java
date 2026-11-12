package boutique.api;

import java.net.URI;
import java.time.Duration;

import jakarta.validation.Valid;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * La ressource « commandes » : des noms dans l'URI, l'action dans le verbe HTTP, le resultat dans le code.
 *
 * <pre>
 * GET    /commandes                    lister (pagine, filtre par statut)   200
 * GET    /commandes/{id}               lire                                 200, 404
 * POST   /commandes                    creer                                201 + Location, 400
 * PUT    /commandes/{id}               remplacer entierement                200, 400, 404
 * PATCH  /commandes/{id}               modifier une partie                  200, 404
 * DELETE /commandes/{id}               supprimer (jeton admin)              204, 401, 403, 404, 409
 * POST   /commandes/{id}/expedition    une action qui n'est pas un CRUD     200, 404
 * </pre>
 */
@RestController
@RequestMapping("/commandes")
public class CommandeController {

    private static final int TAILLE_MAX = 100;

    private final CommandeStore store;
    private final Idempotence idempotence;
    private final Duration latence;

    public CommandeController(CommandeStore store, Idempotence idempotence,
                              @Value("${boutique.latence:0ms}") Duration latence) {
        this.store = store;
        this.idempotence = idempotence;
        this.latence = latence;
    }

    @GetMapping
    public Page<Commande> lister(@RequestParam(required = false) Commande.Statut statut,
                                 @RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "20") int taille) {
        // Une page d'un million de lignes, c'est un deni de service : la taille est plafonnee.
        int t = Math.clamp(taille, 1, TAILLE_MAX);
        int p = Math.max(page, 0);
        var toutes = store.lister(statut);
        int debut = (int) Math.min((long) p * t, toutes.size());
        return new Page<>(toutes.subList(debut, Math.min(debut + t, toutes.size())), p, t, toutes.size());
    }

    @GetMapping(path = "/{id}", version = "1")
    public Commande lire(@PathVariable long id) {
        return trouver(id);
    }

    /** Version 2 : le champ « article » s'appelle « produit ». Les clients de la version 1 ne cassent pas. */
    @GetMapping(path = "/{id}", version = "2")
    public CommandeV2 lireV2(@PathVariable long id) {
        Commande c = trouver(id);
        return new CommandeV2(c.id(), c.client(), c.article(), c.quantite(), c.statut());
    }

    public record CommandeV2(long id, String client, String produit, int quantite, Commande.Statut statut) {
    }

    @PostMapping
    public ResponseEntity<Commande> creer(@Valid @RequestBody CommandeDemande demande,
                                          @RequestHeader(name = "Idempotency-Key", required = false) String cle) {
        Commande c = idempotence.executer(cle, demande, () -> {
            Commande creee = store.creer(demande);
            pause(); // un traitement lent apres la creation (paiement, stock...) : le client peut abandonner
            return creee;
        });
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(c.id()).toUri();
        return ResponseEntity.created(location).body(c);
    }

    @PutMapping("/{id}")
    public Commande remplacer(@PathVariable long id, @Valid @RequestBody CommandeDemande demande) {
        return store.modifier(id, c -> new Commande(id, demande.client(), demande.article(), demande.quantite(), c.statut()))
                .orElseThrow(() -> introuvable(id));
    }

    @PatchMapping("/{id}")
    public Commande modifier(@PathVariable long id, @Valid @RequestBody CommandeModif modif) {
        return store.modifier(id, c -> new Commande(id,
                        modif.client() != null ? modif.client() : c.client(),
                        modif.article() != null ? modif.article() : c.article(),
                        modif.quantite() != null ? modif.quantite() : c.quantite(),
                        c.statut()))
                .orElseThrow(() -> introuvable(id));
    }

    @PostMapping("/{id}/expedition")
    public Commande expedier(@PathVariable long id) {
        return store.modifier(id, c -> c.avec(Commande.Statut.EXPEDIEE)).orElseThrow(() -> introuvable(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> supprimer(@PathVariable long id,
                                          @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String jeton) {
        // 401 : on ne sait pas qui vous etes (jeton absent ou inconnu). 403 : on le sait, et c'est non.
        // (Un vrai projet passe par Spring Security ; ici, deux jetons en dur pour voir les deux codes.)
        switch (role(jeton)) {
            case "admin" -> { }
            case "lecture" -> throw erreur(HttpStatus.FORBIDDEN, "Ce jeton ne permet pas de supprimer une commande.");
            default -> {
                ErrorResponseException e = erreur(HttpStatus.UNAUTHORIZED,
                        jeton == null ? "Jeton absent : authentifiez-vous." : "Jeton inconnu : authentifiez-vous.");
                e.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, jeton == null ? "Bearer" : "Bearer error=\"invalid_token\"");
                throw e;
            }
        }
        boolean supprimee = store.supprimer(id, c -> {
            if (c.statut() == Commande.Statut.EXPEDIEE) {
                throw erreur(HttpStatus.CONFLICT, "La commande " + id + " est deja expediee : elle ne peut plus etre supprimee.");
            }
        });
        if (!supprimee) {
            throw introuvable(id);
        }
        return ResponseEntity.noContent().build();
    }

    private Commande trouver(long id) {
        return store.trouver(id).orElseThrow(() -> introuvable(id));
    }

    private static ErrorResponseException introuvable(long id) {
        return erreur(HttpStatus.NOT_FOUND, "Aucune commande " + id + ".");
    }

    /** Le role porte par l'en-tete Authorization (« Bearer admin »), ou "" si absent ou inconnu. Le schema
     *  « Bearer » ne depend pas de la casse (RFC 9110), le jeton si. */
    private static String role(String autorisation) {
        if (autorisation == null) {
            return "";
        }
        String[] morceaux = autorisation.trim().split("\\s+", 2);
        if (morceaux.length != 2 || !morceaux[0].equalsIgnoreCase("Bearer")) {
            return "";
        }
        return switch (morceaux[1]) {
            case "admin", "lecture" -> morceaux[1];
            default -> "";
        };
    }

    private static ErrorResponseException erreur(HttpStatus statut, String detail) {
        return new ErrorResponseException(statut, ProblemDetail.forStatusAndDetail(statut, detail), null);
    }

    private void pause() {
        if (latence.isZero()) {
            return;
        }
        try {
            Thread.sleep(latence);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
