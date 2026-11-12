package boutique.api;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ce qu'il ne faut PAS faire : une erreur renvoyee avec le code 200.
 * Le client, les caches, le monitoring et curl --fail croient tous que la requete a reussi.
 */
@RestController
public class AncienController {

    private final CommandeStore store;

    public AncienController(CommandeStore store) {
        this.store = store;
    }

    @GetMapping("/ancien/commandes/{id}")
    public Object lire(@PathVariable long id) {
        return store.trouver(id).<Object>map(c -> c)
                .orElse(Map.of("succes", false, "erreur", "commande introuvable"));
    }
}
