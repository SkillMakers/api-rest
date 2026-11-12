package boutique.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Chaque comportement montre dans la video, verifie sur l'API demarree (port aleatoire, vrai HTTP).
 * Les corps sont compares comme du texte : le test lit la reponse comme un client la recoit.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiTest {

    private static final String LEA = "{\"client\":\"Lea\",\"article\":\"clavier\",\"quantite\":1}";

    @LocalServerPort
    int port;

    @Autowired
    CommandeStore store;

    @Autowired
    Idempotence idempotence;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> envoyer(String methode, String chemin, String corps, String... entetes) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + chemin));
        if (corps != null) {
            b.header("Content-Type", "application/json");
        }
        for (int i = 0; i < entetes.length; i += 2) {
            b.header(entetes[i], entetes[i + 1]);
        }
        b.method(methode, corps == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(corps));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private long creer(String corps) throws Exception {
        String loc = envoyer("POST", "/commandes", corps).headers().firstValue("Location").orElseThrow();
        return Long.parseLong(loc.substring(loc.lastIndexOf('/') + 1));
    }

    @Nested
    class Verbes {

        @Test
        @DisplayName("POST : 201 Created, et l'en-tete Location donne l'adresse de la nouvelle commande")
        void postCree() throws Exception {
            // GIVEN
            String corps = LEA;

            // WHEN
            var r = envoyer("POST", "/commandes", corps);

            // THEN
            assertThat(r.statusCode()).isEqualTo(201);
            assertThat(r.headers().firstValue("Location").orElseThrow()).matches("http://localhost:\\d+/commandes/\\d+");
            assertThat(r.body()).contains("\"statut\":\"EN_PREPARATION\"");
        }

        @Test
        @DisplayName("PATCH obeit aux memes regles que POST : un client vide est refuse (400)")
        void patchClientVide() throws Exception {
            // GIVEN
            long id = creer(LEA);

            // WHEN
            var r = envoyer("PATCH", "/commandes/" + id, "{\"client\":\"  \"}");

            // THEN
            assertThat(r.statusCode()).isEqualTo(400);
            assertThat(r.body()).contains("\"client\":\"ne doit pas etre vide\"");
        }

        @Test
        @DisplayName("PUT remplace tout : un corps partiel est refuse (400), PATCH le complete (200)")
        void putPartielRefusePatchAccepte() throws Exception {
            // GIVEN
            long id = creer(LEA);

            // WHEN
            var put = envoyer("PUT", "/commandes/" + id, "{\"quantite\":3}");
            var patch = envoyer("PATCH", "/commandes/" + id, "{\"quantite\":3}");

            // THEN
            assertThat(put.statusCode()).isEqualTo(400);
            assertThat(patch.statusCode()).isEqualTo(200);
            assertThat(patch.body()).contains("\"client\":\"Lea\"", "\"quantite\":3");
        }
    }

    @Nested
    class Codes {

        @Test
        @DisplayName("404 au format Problem Details (application/problem+json)")
        void introuvable() throws Exception {
            // GIVEN aucune commande 999

            // WHEN
            var r = envoyer("GET", "/commandes/999", null);

            // THEN
            assertThat(r.statusCode()).isEqualTo(404);
            assertThat(r.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
            assertThat(r.body()).contains("\"detail\":\"Aucune commande 999.\"", "\"status\":404");
        }

        @Test
        @DisplayName("400 : la reponse dit quels champs sont faux")
        void validation() throws Exception {
            // GIVEN
            String corps = "{\"client\":\"\",\"article\":\"souris\",\"quantite\":0}";

            // WHEN
            var r = envoyer("POST", "/commandes", corps);

            // THEN
            assertThat(r.statusCode()).isEqualTo(400);
            assertThat(r.body()).contains("\"erreurs\"", "\"client\"", "\"quantite\"");
        }

        @Test
        @DisplayName("400 : une quantite absente est signalee « obligatoire », pas changee en 0")
        void quantiteAbsente() throws Exception {
            // GIVEN
            String sansQuantite = "{\"client\":\"Lea\",\"article\":\"clavier\"}";
            String quantiteNulle = "{\"client\":\"Lea\",\"article\":\"clavier\",\"quantite\":null}";

            // WHEN
            var absente = envoyer("POST", "/commandes", sansQuantite);
            var nulle = envoyer("POST", "/commandes", quantiteNulle);

            // THEN
            assertThat(absente.statusCode()).isEqualTo(400);
            assertThat(absente.body()).contains("\"quantite\":\"obligatoire\"");
            assertThat(nulle.body()).contains("\"quantite\":\"obligatoire\"");
        }

        @Test
        @DisplayName("DELETE : un jeton inconnu est un 401 (on ne sait pas qui vous etes), pas un 403")
        void jetonInconnu() throws Exception {
            // GIVEN
            long id = creer(LEA);

            // WHEN
            var r = envoyer("DELETE", "/commandes/" + id, null, "Authorization", "Bearer n-importe-quoi");

            // THEN
            assertThat(r.statusCode()).isEqualTo(401);
            assertThat(r.headers().firstValue("WWW-Authenticate")).contains("Bearer error=\"invalid_token\"");
        }

        @Test
        @DisplayName("DELETE : 401 sans jeton (avec WWW-Authenticate), 403 avec un jeton sans le droit")
        void unauthorizedEtForbidden() throws Exception {
            // GIVEN
            long id = creer(LEA);

            // WHEN
            var sans = envoyer("DELETE", "/commandes/" + id, null);
            var lecture = envoyer("DELETE", "/commandes/" + id, null, "Authorization", "Bearer lecture");

            // THEN
            assertThat(sans.statusCode()).isEqualTo(401);
            assertThat(sans.headers().firstValue("WWW-Authenticate")).contains("Bearer");
            assertThat(lecture.statusCode()).isEqualTo(403);
        }

        @Test
        @DisplayName("DELETE : 409 si la commande est expediee ; sinon 204, puis 404 la 2e fois")
        void conflitPuisSuppression() throws Exception {
            // GIVEN
            long expediee = creer(LEA);
            envoyer("POST", "/commandes/" + expediee + "/expedition", null);
            long libre = creer(LEA);

            // WHEN
            var conflit = envoyer("DELETE", "/commandes/" + expediee, null, "Authorization", "Bearer admin");
            var premier = envoyer("DELETE", "/commandes/" + libre, null, "Authorization", "Bearer admin");
            var second = envoyer("DELETE", "/commandes/" + libre, null, "Authorization", "Bearer admin");

            // THEN
            assertThat(conflit.statusCode()).isEqualTo(409);
            assertThat(premier.statusCode()).isEqualTo(204);
            assertThat(second.statusCode()).isEqualTo(404);
        }

        @Test
        @DisplayName("L'anti-modele : une erreur renvoyee avec 200, invisible pour le client")
        void deuxCentQuiMent() throws Exception {
            // GIVEN aucune commande 999

            // WHEN
            var r = envoyer("GET", "/ancien/commandes/999", null);

            // THEN
            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(r.body()).contains("\"erreur\":\"commande introuvable\"");
        }
    }

    @Nested
    class PaginationEtVersions {

        @Test
        @DisplayName("Pagination : la taille demandee est plafonnee a 100")
        void taillePlafonnee() throws Exception {
            // GIVEN
            creer(LEA);

            // WHEN
            var r = envoyer("GET", "/commandes?taille=1000000", null);

            // THEN
            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(r.body()).contains("\"taille\":100");
        }

        @Test
        @DisplayName("Versionnement : sans en-tete, la v1 (article) ; API-Version: 2, la v2 (produit) ; 3 inconnue, 400")
        void versions() throws Exception {
            // GIVEN
            long id = creer(LEA);

            // WHEN
            var v1 = envoyer("GET", "/commandes/" + id, null);
            var v2 = envoyer("GET", "/commandes/" + id, null, "API-Version", "2");
            var v3 = envoyer("GET", "/commandes/" + id, null, "API-Version", "3");

            // THEN
            assertThat(v1.body()).contains("\"article\":\"clavier\"").doesNotContain("produit");
            assertThat(v2.body()).contains("\"produit\":\"clavier\"").doesNotContain("article");
            assertThat(v3.statusCode()).isEqualTo(400);
        }
    }

    @Nested
    class Idempotence_ {

        @Test
        @DisplayName("Meme Idempotency-Key deux fois : une seule commande, la meme reponse")
        void memeCle() throws Exception {
            // GIVEN
            int avant = store.lister(null).size();

            // WHEN
            var a = envoyer("POST", "/commandes", LEA, "Idempotency-Key", "cle-test-1");
            var b = envoyer("POST", "/commandes", LEA, "Idempotency-Key", "cle-test-1");

            // THEN
            assertThat(a.headers().firstValue("Location")).isEqualTo(b.headers().firstValue("Location"));
            assertThat(store.lister(null)).hasSize(avant + 1);
        }

        @Test
        @DisplayName("La meme cle avec une autre commande : 422, et rien n'est cree")
        void memeCleAutreCorps() throws Exception {
            // GIVEN
            envoyer("POST", "/commandes", LEA, "Idempotency-Key", "cle-test-3");
            int avant = store.lister(null).size();

            // WHEN
            var r = envoyer("POST", "/commandes", "{\"client\":\"Hugo\",\"article\":\"ecran\",\"quantite\":1}",
                    "Idempotency-Key", "cle-test-3");

            // THEN
            assertThat(r.statusCode()).isEqualTo(422);
            assertThat(store.lister(null)).hasSize(avant);
        }

        @Test
        @DisplayName("Deux requetes simultanees avec la meme cle : la creation n'a lieu qu'une fois")
        void simultanees() {
            // GIVEN une creation lente, comptee
            AtomicInteger creations = new AtomicInteger();
            CommandeDemande corps = new CommandeDemande("Lea", "clavier", 1);
            Runnable lente = () -> idempotence.executer("cle-test-2", corps, () -> {
                creations.incrementAndGet();
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new Commande(0, "Lea", "clavier", 1, Commande.Statut.EN_PREPARATION);
            });

            // WHEN
            CompletableFuture.allOf(CompletableFuture.runAsync(lente), CompletableFuture.runAsync(lente)).join();

            // THEN
            assertThat(creations).hasValue(1);
        }
    }
}
