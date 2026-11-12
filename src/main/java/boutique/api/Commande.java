package boutique.api;

/** Une commande telle que l'API la renvoie (version 1 de l'API). */
public record Commande(long id, String client, String article, int quantite, Statut statut) {

    public enum Statut { EN_PREPARATION, EXPEDIEE }

    Commande avec(Statut nouveau) {
        return new Commande(id, client, article, quantite, nouveau);
    }
}
