#!/bin/sh
# Compile, teste, lance l'API et joue chaque scenario avec curl. Sorties dans preuves/.
# A lancer par : docker compose run --rm demo   (voir le README)
set -eu
cd "$(dirname "$0")"
P=preuves
mkdir -p "$P"
API=http://localhost:8080
LENTE=http://localhost:8081

{
  echo "\$ java -version"; java -version 2>&1
  echo "\$ mvn -version | head -1"; mvn -version | head -1
} > "$P/versions-outils.txt"

# Les journaux de Maven et des tests vont dans /tmp ; la preuve ne garde qu'un test par ligne, puis le total.
mvn -q -B clean verify > /tmp/mvn.log 2>&1 || { cat /tmp/mvn.log; exit 1; }
{
  echo "\$ mvn verify   (un test par ligne : classe, methode, duree)"
  cat target/surefire-reports/TEST-*.xml | grep -o '<testcase name="[^"]*" classname="[^"]*" time="[^"]*"' \
    | sed 's/<testcase name="\([^"]*\)" classname="boutique\.api\.\([^"]*\)" time="\([^"]*\)"/ok   \2   \1   (\3 s)/' | sort
  echo "Total : $(cat target/surefire-reports/TEST-*.xml | grep -c '<testcase ') tests, $(cat target/surefire-reports/TEST-*.xml | grep -c -E '<(failure|error)') en echec"
} > "$P/tests.txt"

JAR=target/api-rest-1.0.jar
java -jar $JAR --server.port=8080 > /tmp/api.log 2>&1 &
PIDS=$!
# La meme API, mais lente : 1,5 seconde de traitement apres la creation d'une commande (paiement, stock...)
java -jar $JAR --server.port=8081 --boutique.latence=1500ms > /tmp/lente.log 2>&1 &
PIDS="$PIDS $!"
trap 'kill $PIDS 2>/dev/null || true' EXIT

attendre() {
  i=0
  until curl -s -o /dev/null "http://localhost:$1/commandes"; do
    i=$((i+1)); [ $i -lt 120 ] || { echo "port $1 muet"; exit 1; }; sleep 0.5
  done
}
attendre 8080; attendre 8081

# Un appel, ecrit comme dans un terminal : la commande, le code HTTP, les en-tetes utiles, le corps.
appel() {
  ligne="\$ curl"
  # guillemets autour de tout ce que le shell interpreterait (&, ?, espaces, accolades...) : copiable tel quel
  for a in "$@"; do case "$a" in *[!A-Za-z0-9_./:=-]*) ligne="$ligne '$a'" ;; *) ligne="$ligne $a" ;; esac; done
  echo "$ligne"
  : > /tmp/corps; : > /tmp/entetes
  code=$(curl -s -o /tmp/corps -D /tmp/entetes -w '%{http_code}' "$@" || true)
  echo "HTTP $code"
  grep -i -E '^(location|www-authenticate|allow|content-type):' /tmp/entetes | tr -d '\r' || true
  [ -s /tmp/corps ] && { cat /tmp/corps; echo; }
  echo
}
JSON="Content-Type: application/json"

# --- Les verbes sur une ressource -------------------------------------------------------------------
{
  appel -X POST $API/commandes -H "$JSON" -d '{"client":"Lea","article":"clavier","quantite":1}'
  appel $API/commandes/1
  appel -X PUT $API/commandes/1 -H "$JSON" -d '{"client":"Lea","article":"clavier","quantite":2}'
  appel -X PUT $API/commandes/1 -H "$JSON" -d '{"quantite":3}'
  appel -X PATCH $API/commandes/1 -H "$JSON" -d '{"quantite":3}'
  appel -X POST $API/commandes/1/expedition
} > "$P/verbes.txt"

# --- Les codes d'erreur -----------------------------------------------------------------------------
{
  appel $API/commandes/999
  appel -X POST $API/commandes -H "$JSON" -d '{"client":"","article":"souris","quantite":0}'
  appel -X POST $API/commandes -H "$JSON" -d '{"client":"Lea","article":"souris"}'
  appel -X PATCH $API/commandes/1 -H "$JSON" -d '{"client":"  "}'
  appel -X POST $API/commandes -H "Content-Type: text/plain" -d 'une commande'
  appel -X POST $API/commandes/1
  appel -X DELETE $API/commandes/1
  appel -X DELETE $API/commandes/1 -H "Authorization: Bearer n-importe-quoi"
  appel -X DELETE $API/commandes/1 -H "Authorization: Bearer lecture"
  appel -X DELETE $API/commandes/1 -H "Authorization: Bearer admin"
  appel -X POST $API/commandes -H "$JSON" -d '{"client":"Hugo","article":"ecran","quantite":1}'
  appel -X DELETE $API/commandes/2 -H "Authorization: Bearer admin"
  appel -X DELETE $API/commandes/2 -H "Authorization: Bearer admin"
} > "$P/codes.txt"

# --- Le 200 qui ment : une erreur renvoyee avec le code 200 -----------------------------------------
{
  appel $API/ancien/commandes/999
  echo "\$ curl --fail -s $API/ancien/commandes/999 > /dev/null ; echo \"code de sortie : \$?\""
  set +e; curl --fail -s $API/ancien/commandes/999 > /dev/null; echo "code de sortie : $?"; set -e
  echo
  echo "\$ curl --fail -s $API/commandes/999 > /dev/null ; echo \"code de sortie : \$?\""
  set +e; curl --fail -s $API/commandes/999 > /dev/null; echo "code de sortie : $?"; set -e
} > "$P/deux-cent-qui-ment.txt"

# --- Pagination et filtres --------------------------------------------------------------------------
for nom in Ines Tom Sara Noe Jade; do
  curl -s -o /dev/null -X POST $API/commandes -H "$JSON" -d "{\"client\":\"$nom\",\"article\":\"cable\",\"quantite\":1}"
done
curl -s -o /dev/null -X POST $API/commandes/4/expedition
{
  appel "$API/commandes?page=0&taille=2"
  appel "$API/commandes?page=1&taille=2"
  appel "$API/commandes?statut=EXPEDIEE"
  appel "$API/commandes?taille=1000000"
} > "$P/pagination.txt"

# --- Versionnement ----------------------------------------------------------------------------------
{
  appel $API/commandes/3
  appel $API/commandes/3 -H "API-Version: 2"
  appel $API/commandes/3 -H "API-Version: 3"
} > "$P/versions.txt"

# --- Le piege : un POST rejoue apres un timeout -----------------------------------------------------
# Le client abandonne au bout d'1 seconde et recommence ; le serveur, lui, a deja cree la commande.
rejouer() {  # $1 = corps JSON, puis les en-tetes en plus ; 2 tentatives avec 1 s de delai max chacune
  corps=$1; shift
  n=1
  while [ $n -le 2 ]; do
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 1 -X POST $LENTE/commandes -H "$JSON" "$@" \
      -d "$corps" || true)
    echo "tentative $n : ${code:-000}$( [ "$code" = 000 ] && echo ' (timeout, le client abandonne)' )"
    n=$((n+1))
  done
  sleep 3   # laisser le serveur finir
}
{
  echo "\$ POST /commandes, timeout client 1 s, serveur lent (1,5 s), 2 tentatives, SANS cle d'idempotence"
  rejouer '{"client":"Lea","article":"clavier","quantite":1}'
  appel "$LENTE/commandes?taille=10"
  echo "\$ la meme chose pour Hugo, AVEC Idempotency-Key: 5f1c-commande-hugo (la meme cle aux deux tentatives)"
  HUGO='{"client":"Hugo","article":"ecran","quantite":1}'
  rejouer "$HUGO" -H "Idempotency-Key: 5f1c-commande-hugo"
  appel "$LENTE/commandes?taille=10"
  echo "\$ une 3e tentative avec la meme cle, sans timeout cette fois"
  appel -X POST $LENTE/commandes -H "$JSON" -H "Idempotency-Key: 5f1c-commande-hugo" -d "$HUGO"
  echo "\$ la meme cle, mais pour une autre commande (une erreur du client)"
  appel -X POST $LENTE/commandes -H "$JSON" -H "Idempotency-Key: 5f1c-commande-hugo" -d '{"client":"Hugo","article":"souris","quantite":2}'
} > "$P/piege-idempotence.txt"

echo "Preuves ecrites dans $P/ :"; ls "$P"
