# API REST : verbes, codes HTTP et pièges

Code de la vidéo **API REST : c'est quoi ? Le cours complet** de la chaîne
[IT Skills Maker](https://www.youtube.com/@ItSkillsMaker) (playlist
[Microservices et Spring Boot](https://www.youtube.com/playlist?list=PLe6-9MbhEih4yTttnjq4_qtdUs_5DBIx0)).

Une petite API de commandes en Spring Boot 4.1 et Java 25. Chaque règle de la vidéo y est provoquée pour de vrai,
et les sorties réelles de `curl` sont dans [`preuves/`](preuves/).

## Lancer

Rien à installer à part Docker :

```
docker compose run --rm demo
```

Le conteneur compile, lance les 15 tests, démarre l'API (port 8080) et une copie lente (port 8081, 1,5 s de traitement
après chaque création), puis joue chaque scénario avec `curl`. Les sorties remplacent celles de `preuves/`.

Avec Java 25 et Maven installés : `mvn verify`, puis `java -jar target/api-rest-1.0.jar`.

## Les routes

| Requête | Rôle | Codes |
|---|---|---|
| `GET /commandes?page=0&taille=20&statut=EXPEDIEE` | lister (taille plafonnée à 100) | 200 |
| `GET /commandes/{id}` | lire (en-tête `API-Version: 1` ou `2`) | 200, 400, 404 |
| `POST /commandes` | créer (en-tête `Idempotency-Key` facultatif) | 201 + `Location`, 400, 415, 422 |
| `PUT /commandes/{id}` | remplacer entièrement | 200, 400, 404 |
| `PATCH /commandes/{id}` | modifier une partie | 200, 400, 404 |
| `DELETE /commandes/{id}` | supprimer (`Authorization: Bearer admin`) | 204, 401, 403, 404, 409 |
| `POST /commandes/{id}/expedition` | une action qui n'est pas un CRUD | 200, 404 |
| `GET /ancien/commandes/{id}` | l'anti-modèle : une erreur renvoyée avec 200 | 200 |

Les erreurs suivent le format Problem Details (RFC 9457, `application/problem+json`).

## Les preuves

| Fichier | Ce qu'il montre |
|---|---|
| `verbes.txt` | POST 201 + `Location`, PUT partiel refusé (400), PATCH accepté |
| `codes.txt` | 400, 404, 405 + `Allow`, 415, 409, 401 sans jeton et avec un jeton inconnu, 403 |
| `deux-cent-qui-ment.txt` | `curl --fail` ne voit pas l'erreur renvoyée avec 200 (code de sortie 0), mais voit le 404 (22) |
| `pagination.txt` | pages, filtre par statut, `taille=1000000` ramenée à 100 |
| `versions.txt` | v1 `article`, v2 `produit`, version 3 inconnue = 400 |
| `piege-idempotence.txt` | un client qui abandonne au bout d'1 s et réessaie : 2 commandes sans clé, 1 seule avec `Idempotency-Key` ; même clé avec une autre commande = 422 |
| `tests.txt` | les 15 tests, un par ligne |

## Ce que la démo simplifie

- Les commandes et les clés d'idempotence sont en mémoire. En production, les clés vont dans une base partagée par
  tous les serveurs, avec une durée de vie (24 h chez Stripe).
- Deux jetons en dur (`admin`, `lecture`) pour montrer 401 et 403 ; un vrai projet passe par Spring Security.
- Seule la lecture d'une commande a une version 2.
