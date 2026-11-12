package boutique.api;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponseException;

/**
 * Cles d'idempotence : le client envoie la meme cle (en-tete Idempotency-Key) quand il rejoue un POST.
 * La premiere requete cree la commande ; les suivantes recoivent la meme reponse, sans en creer une autre.
 *
 * - Une requete qui arrive pendant que la premiere tourne encore attend sa fin (une seule tache par cle),
 *   au lieu de creer un doublon. Les requetes qui portent d'autres cles ne sont jamais bloquees.
 * - La meme cle avec un autre corps est une erreur du client : 422 (Stripe fait de meme).
 * - Comme chez Stripe, un rejeu renvoie la reponse d'origine, meme si la commande a change depuis.
 * - En production, les cles vont dans une base partagee, avec une duree de vie (Stripe : 24 h).
 *   Ici : en memoire, sans fin.
 */
@Component
public class Idempotence {

    private record Entree(Object corps, CompletableFuture<Commande> resultat) {
    }

    private final Map<String, Entree> cles = new ConcurrentHashMap<>();

    public Commande executer(String cle, Object corps, Supplier<Commande> creation) {
        if (cle == null || cle.isBlank()) {
            return creation.get();
        }
        Entree nouvelle = new Entree(corps, new CompletableFuture<>());
        Entree existante = cles.putIfAbsent(cle, nouvelle);
        if (existante != null) {
            if (!existante.corps().equals(corps)) {
                throw new ErrorResponseException(HttpStatus.UNPROCESSABLE_CONTENT, ProblemDetail.forStatusAndDetail(
                        HttpStatus.UNPROCESSABLE_CONTENT, "Cette Idempotency-Key a deja servi pour une autre commande."), null);
            }
            return attendre(existante.resultat());
        }
        try {
            Commande c = creation.get();
            nouvelle.resultat().complete(c);
            return c;
        } catch (RuntimeException e) {
            cles.remove(cle, nouvelle);              // echec : le client pourra reessayer avec la meme cle
            nouvelle.resultat().completeExceptionally(e);
            throw e;
        }
    }

    private static Commande attendre(CompletableFuture<Commande> resultat) {
        try {
            return resultat.join();
        } catch (CompletionException e) {
            throw e.getCause() instanceof RuntimeException r ? r : e;
        }
    }
}
