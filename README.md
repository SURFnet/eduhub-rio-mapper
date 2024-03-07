# SURFeduhub RIO mapper

## Documentatie

### Bijgesloten

- [Ontwerp](doc/design/rio-mapper-ontwerp.pdf)
- [RIO informatiemodel](doc/RIO-Informatiemodel/Informatiemodel_RIO.pdf)
- [RIO raadplegen webservices](doc/RIO-Webservicekoppeling-Beheren-en-Raadplegen/Webservice_documentatie_-_DUO_RIO_Raadplegen_OnderwijsOrganisatie_V4.pdf)
- [RIO beheren webservices](doc/RIO-Webservicekoppeling-Beheren-en-Raadplegen/Webservice_documentatie_-_DUO_RIO_Beheren_OnderwijsOrganisatie_V4.pdf)

### Extern

- [OOAPI repository](https://github.com/open-education-api/specification)
- [OOAPI doc](https://open-education-api.github.io/specification/v5-beta/docs.html)
- [OOAPI doc/consumers-and-profiles](https://openonderwijsapi.nl/specification/#/technical/consumers-and-profiles)
- [OOAPI doc/consumers-and-profiles/rio](https://openonderwijsapi.nl/#/technical/consumers-and-profiles/rio)

# Doel voor project

Doel 1: mapper functie om top-level OOAPI objecten over te zetten
naar RIO objecten (een richting). Deze werkt puur met clojure data
structures (doet geen file IO of API calls).

RIO objecten worden uiteindelijk geserializeerd naar XML.

OOAPI objecten komen binnen als JSON.

Het gaat om de volgende top-level OOAPI objecten:

 - EducationSpecification

 - Education (met gerelateerde EducationOfferings)

We beginnen met de EducationSpecification want deze is het
eenvoudigst.

# Data flow (uiteindelijke app)

- Get OOAPI JSON via Edhuhub Gateway

- OOAPI JSON -> OOAPI Data (via standaard json -> clojure reader)

- Validate OOAPI data met spec, error als niet valid

- Verkrijg RIO Ids map voor alle relevante objecten RIO resolver API.

- OOAPI Data + RIO IDs -> RIO Data (pure functie)

- Validate RIO Data (voor testing, bij dev gebruiken we generative
  testing en dan zou dit in prod uit kunnen staan)

- RIO Data serialisatie naar RIO XML (misschien via hiccup-style
  "tussen format")

- RIO XML valideren dmv XSD

- Post RIO XML naar API

# Implementatie stappen (mapper zonder API calls)

## Mapper van pure data

- voorbeeld van OOAPI Data opschrijven

- voorbeeld van RIO Data equivalent van bovenstaande OOAPI Data

- specs maken voor OOAPI Data. Spec bevat checks voor required velden
  en types etc. De spec mag stricter zijn dan de OOAPI swagger
  definitie als dat nodig is voor de vertaling (bijv. bepaalde
  attributen mogen dan required zijn voor de mapper).

- specs maken voor RIO Data (clojure idiomatisch, goed te speccen)

- implementatie mapper van `EducationSpecification` OOAPI Data naar
  RIO Data, met tests

- RIO XML serialisatie van bovenstaande RIO Data

- Validate Rio XML met gegeven XSDs

- implementatie mapper van `Education` OOAPI Data naar RIO Data, met
  tests.

- RIO XML serialisatie van bovenstaande RIO Data

- Validate Rio XML met gegeven XSDs

Als tijdens bovenstaande problemen opduiken waarvoor aanpassingen aan
de APIs nodig zijn, dan overleggen we dat met Surf zodat die
aanpassingen ingepland kunnen worden. Doel is dat aanpassingen in niet
meer dan 2 iteraties van de specs doorgevoerd worden. We moeten dit
dus batchen en goed documenteren. Hoe precies moeten we bekijken. In
de tussentijd werken we dan door (laten bepaalde attributen buiten
beschouwing / versimpelen oid), liefst met data die voldoet aan de dan
bestaande specs.

## Api calls

- Test/mock APIs implementeren (OOAPI en Rio), uitzoeken hoe en wat er
  nodig is voor onderstaande stappen. Bijvoorbeeld OOAPI Client en Rio
  Client als protocol definieren en voor tests aparte implementatie(s)
  maken.

- OOAPI API calls doen om data te verzamelen gebaseerd op "pad":
  instelling url/id, type root-object (Education of
  EducationSpecification), root-object id.

- RIO resolver API calls doen om OOAPI id te vertalen naar RIO id.

- RIO API calls doen om RIO XML CRUD acties uit te voeren.

Bij alle API calls onderscheid maken tussen retryable en niet-
retryable errors. Dit moet door caller van de mapper functie verwerkt
worden.

## Demo app

- Demo CLI app; gegeven configuratie en OOAPI pad argumenten moet deze
  een mapping uitvoeren tegen de OOAPI en RIO API's. Evt met retries
  bij errors.

## Aanmaken van keystore

Tijdens development is er een keystore.jks en een truststore.jks nodig
in de root van het project. Deze niet toevoegen aan git!  Om de
keystore te genereren, draai:

```sh
sh dev/create-keystore.sh
```

Om de truststore opnieuw te genereren (zit al in deze repo), draai:

```sh
sh dev/create-truststore.sh
```

## Configuratie

De applicatie wordt geconfigureerd met environment variabelen. De volgende variabelen
moeten ingesteld worden:

```
API_HOSTNAME                        Hostname for listing web API
API_PORT                            HTTP port for serving web API
CLIENTS_INFO_PATH                   CLients info config file
GATEWAY_PASSWORD                    OOAPI Gateway Password
GATEWAY_ROOT_URL                    OOAPI Gateway Root URL
GATEWAY_USER                        OOAPI Gateway Username
HTTP_MESSAGES                       Boolean; should all http traffic be logged? Defaults to false.
JOBS_MAX_RETRIES                    Max number of retries of a failed job
JOBS_RETRY_WAIT_MS                  Number of milliseconds to wait before retrying a failed job
KEYSTORE                            Path to keystore
KEYSTORE_ALIAS                      Key alias in keystore
KEYSTORE_PASSWORD                   Keystore password
REDIS_KEY_PREFIX                    Prefix for redis keys
REDIS_URI                           URI to redis
RIO_RETRY_ATTEMPTS_SECONDS          Comma-separated list of number of seconds to wait after each RIO retry.
RIO_READ_URL                        RIO Services Read URL
RIO_RECIPIENT_OIN                   Recipient OIN for RIO SOAP calls
RIO_UPDATE_URL                      RIO Services Update URL
STATUS_TTL_SEC                      Number of seconds hours to keep job status
SURF_CONEXT_CLIENT_ID               SurfCONEXT client id for Mapper service
SURF_CONEXT_CLIENT_SECRET           SurfCONEXT client secret for Mapper service
SURF_CONEXT_INTROSPECTION_ENDPOINT  SurfCONEXT introspection endpoint
TRUSTSTORE                          Path to trust-store
TRUSTSTORE_PASSWORD                 Trust-store password
```

The `CLIENTS_INFO_PATH` should specify a json file with settings for client-id, schac-home and oin:

```json
{
  "clients": ""
  [{
    "client-id": "rio-mapper-dev.jomco.nl",
    "institution-schac-home": "demo06.test.surfeduhub.nl",
    "institution-oin": "0000000700025BE00000",
    "institution-name": "University of Uitgeest",
    "onderwijsbestuurscode": "123B321",
  },
  ...]
}
```

## Docker containers

De applicatie bestaat uit twee delen: de *API* en de *Worker*.  Beiden
onderdelen kunnen vanuit een enkele docker image opgestart worden
waarvoor een [./Dockerfile](Dockerfile) bijgesloten is.  Zie voor de
configuratie opties de [./CLI.md](CLI) documentatie en merk op dat
deze container allebei toegang nodig hebben tot dezelfde *redis*
server.

Bouw de docker image met:

```sh
docker build -t eduhub-rio-mapper .
```

Draai de API server met:

```sh
docker run \
  -p 8080:8080 \
  -e API_HOSTNAME=0.0.0.0 \
  -e API_PORT=8888 \
  -v config:/config
  --env-file config.env \
  eduhub-rio-mapper \
  serve-api
```

en draai de worker met:

```sh
docker run \
  -v config:/config
  --env-file config.env \
  eduhub-rio-mapper \
  worker
```

Merk op dat `config.env` niet meegeleverd wordt en de configuration
bestanden via een "volume" (zie `-v` optie) beschikbaar moeten worden
gemaakt.


# Reporting vulnerabilities

If you have found a vulnerability in the code, we would like to hear about it so that we can take appropriate measures as quickly as possible. We are keen to cooperate with you to protect users and systems better. See https://www.surf.nl/.well-known/security.txt for information on how to report vulnerabilities responsibly.

# License

Copyright (C) 2022 SURFnet B.V.

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as
published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but
WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public
License along with this program.  If not, see
<https://www.gnu.org/licenses/>.
