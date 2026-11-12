package boutique.api;

import java.util.List;

/** Une page de resultats : le contenu, et de quoi demander la suivante. */
public record Page<T>(List<T> contenu, int page, int taille, long total) {
}
