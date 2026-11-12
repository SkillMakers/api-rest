package boutique.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Le corps d'un POST (creer) ou d'un PUT (remplacer) : tous les champs sont obligatoires.
 * Un PUT remplace la ressource entiere, il ne complete pas : un champ oublie n'est pas « garde », il manque.
 * (La quantite est un Integer, pas un int : une quantite absente doit etre signalee « obligatoire », pas devenir 0.)
 */
public record CommandeDemande(@NotBlank(message = "obligatoire") String client,
                              @NotBlank(message = "obligatoire") String article,
                              @NotNull(message = "obligatoire") @Positive(message = "doit etre superieure a 0") Integer quantite) {
}
