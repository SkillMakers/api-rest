package boutique.api;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * Le corps d'un PATCH : seuls les champs presents (non nuls) sont modifies. Un champ present obeit aux memes
 * regles qu'au POST : sinon, PATCH serait une porte derobee pour enregistrer un client vide.
 */
public record CommandeModif(@Pattern(regexp = ".*\\S.*", message = "ne doit pas etre vide") String client,
                            @Pattern(regexp = ".*\\S.*", message = "ne doit pas etre vide") String article,
                            @Positive(message = "doit etre superieure a 0") Integer quantite) {
}
