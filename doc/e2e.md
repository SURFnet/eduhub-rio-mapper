## End to End tests

Om de end-to-end tests te kunnen draaien moeten de volgende zaken geregeld zijn:

- RIO toegang (zie [test/test-clients.json](test/test-clients.json))

    - `CLIENTS_INFO_PATH` (= `test/test-clients.json`)

    - `KEYSTORE` (met daarin rio_test_surfeduhub_surf_nl certificaat
      met sleutel, zie ook de [README](../README.md) voor instructies voor het genereren)
    - `KEYSTORE_ALIAS` (= `test-surf`)
    - `KEYSTORE_PASSWORD`

    - `TRUSTSTORE` (= `truststore.jks`, bestand staat al in deze repository)
    - `TRUSTSTORE_PASSWORD` (= `xxxxxx`)

    - `RIO_RECIPIENT_OIN`
    - `RIO_SENDER_OIN`
    - `RIO_READ_URL`
    - `RIO_UPDATE_URL`

- SURFconext toegang voor client ID

    - `SURF_CONEXT_CLIENT_ID`
    - `SURF_CONEXT_CLIENT_SECRET`
    - `SURF_CONEXT_INTROSPECTION_ENDPOINT`

    - `CLIENT_ID` (= `rio-mapper-dev.jomco.nl`, SURFconext account met toegang to `SURF_CONEXT_CLIENT_ID`)
    - `CLIENT_SECRET`
    - `TOKEN_ENDPOINT` (URL naar SURFconext token endpoint)

- toegang tot SURF SWIFT Object Store voor opzetten van OOAPI test data

    - `OS_USERNAME`
    - `OS_PASSWORD`
    - `OS_AUTH_URL`
    - `OS_PROJECT_NAME`
    - `OS_CONTAINER_NAME`

- een applicatie account op de test gateway welke toegang geeft tot bovenstaande Object Store

    - `GATEWAY_ROOT_URL`
    - `GATEWAY_USER`
    - `GATEWAY_PASSWORD`

- er draait een lokaal toegankelijke *redis* server

Als het bovenstaande geregeld is kunnen de tests gedraaid worden met:

```sh
lein test :e2e
```

Tot slot is het verstandig om environment variabel
`STORE_HTTP_REQUESTS` op `true` te zetten omdat dat meer informatie
geeft over de gemaakte API calls door de mapper zelf.

Ter referentie zie ook [e2e GH workflow](../.github/workflows/e2e.yml).
