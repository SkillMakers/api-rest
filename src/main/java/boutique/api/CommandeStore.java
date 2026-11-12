package boutique.api;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import org.springframework.stereotype.Component;

/**
 * Les commandes, en memoire : la demo porte sur l'API, pas sur la base de donnees.
 *
 * Chaque modification lit et ecrit la commande en une seule operation atomique (computeIfPresent, compute) :
 * lire puis ecrire en deux temps laisserait une requete concurrente s'intercaler, par exemple un PATCH qui
 * annule une expedition, ou un PUT qui fait revenir une commande supprimee.
 */
@Component
public class CommandeStore {

    private final Map<Long, Commande> commandes = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public Commande creer(CommandeDemande d) {
        long id = sequence.incrementAndGet();
        Commande c = new Commande(id, d.client(), d.article(), d.quantite(), Commande.Statut.EN_PREPARATION);
        commandes.put(id, c);
        return c;
    }

    public Optional<Commande> trouver(long id) {
        return Optional.ofNullable(commandes.get(id));
    }

    /** Applique la modification si la commande existe ; vide sinon. */
    public Optional<Commande> modifier(long id, UnaryOperator<Commande> modification) {
        return Optional.ofNullable(commandes.computeIfPresent(id, (k, c) -> modification.apply(c)));
    }

    /**
     * Supprime la commande si elle existe et si `verification` ne leve pas d'exception (la verification voit
     * l'etat courant, sous le meme verrou que la suppression). Renvoie false si la commande n'existe pas.
     */
    public boolean supprimer(long id, Consumer<Commande> verification) {
        boolean[] trouvee = {false};
        commandes.compute(id, (k, c) -> {
            if (c == null) {
                return null;
            }
            verification.accept(c);   // une exception ici laisse la commande en place
            trouvee[0] = true;
            return null;
        });
        return trouvee[0];
    }

    /** Toutes les commandes (filtrees par statut si demande), triees par id. */
    public List<Commande> lister(Commande.Statut statut) {
        return commandes.values().stream()
                .filter(c -> statut == null || c.statut() == statut)
                .sorted(Comparator.comparingLong(Commande::id))
                .toList();
    }
}
