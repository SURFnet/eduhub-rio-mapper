In order to run the end-to-end tests, the following things should have been taken care of:

- RIO access (see [test/test-clients.json](test/test-clients.json))

    - `CLIENTS_INFO_PATH` (= `test/test-clients.json`)

    - `KEYSTORE` (including `rio_test_surfeduhub_surf_nl` certificate with key, see also [README](../README.md) for instructions to generate this file)
    - `KEYSTORE_ALIAS` (= `test-surf`)
    - `KEYSTORE_PASSWORD`

    - `TRUSTSTORE` (= `truststore.jks`, already part of this repository)
    - `TRUSTSTORE_PASSWORD` (= `xxxxxx`)

    - `RIO_RECIPIENT_OIN`
    - `RIO_SENDER_OIN`
    - `RIO_READ_URL`
    - `RIO_UPDATE_URL`

- SURFconext access for client ID "rio-mapper-dev.jomco.nl" (see also [test/test-clients.json](test/test-clients.json))

    - `SURF_CONEXT_CLIENT_ID`
    - `SURF_CONEXT_CLIENT_SECRET`
    - `SURF_CONEXT_INTROSPECTION_ENDPOINT`

    - `CLIENT_ID` (= `rio-mapper-dev.jomco.nl`, SURFconext account with access to `SURF_CONEXT_CLIENT_ID`)
    - `CLIENT_SECRET`
    - `TOKEN_ENDPOINT` (URL to SURFconext token endpoint)

- access to SURF SWIFT Object Store

    - `OS_USERNAME`
    - `OS_PASSWORD`
    - `OS_AUTH_URL`
    - `OS_PROJECT_NAME`
    - `OS_CONTAINER_NAME`

- an application account on the test gateway which provides access to the above Object Store

    - `GATEWAY_ROOT_URL`
    - `GATEWAY_USER`
    - `GATEWAY_PASSWORD`

If the mapper is running locally (happens automatically):

- a locally accessible *redis* server is running

When the above has been done, tests can be run using:

```sh
make test-e2e
```

For debugging set environment variable `STORE_HTTP_REQUESTS` to `true` to get extra information about the API calls done by the mapper.

See also [e2e GH workflow](../.github/workflows/e2e.yml).
