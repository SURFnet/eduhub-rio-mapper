# Plan van aanpak: Rio Mapper update naar OOAPI v6

In dit document beschrijven we de aanpak voor het ontwikkelen van een nieuwe versie van de Eduhub RIO Mapper, met als belangrijkste wijziging een upgrade naar de Open Onderwijs API versie 6.

Dit plan is een weergave van de consensus van de verschillende stakeholders (in ieder geval de opdrachtgever en het implementatieteam). Op basis van dit plan kan een inschatting worden gemaakt van de benodigde ontwikkelcapaciteit en de verwachte doorlooptijd.

# Aanpak

## Architectuur en context

Uitrollen nieuwe versie en uitfaseren oude versie.

De overgang van OOAPI v5 naar OOAPI v6 zal een aanzienlijke tijd duren. Onderwijsinstellingen moeten hun API's bijwerken en zullen dat allemaal volgens hun eigen planning (en die van hun leveranciers) doen. Het is dus belangrijk dat we instellingen de mogelijkheid bieden om snel aan de slag te gaan met v6, maar ook om zo lang als nodig is (te bepalen door de opdrachtgever) de OOAPI v5 interfaces betrouwbaar te ondersteunen, inclusief updates voor beveiliging, aanpassingen aan externe koppelingen, enz.

Voor de RIO Mapper is het daarbij relevant dat de RIO API ook op een eigen tijdpad doorontwikkeld wordt. Zolang OOAPI v5 en v6 diensten aangeboden worden, moet de RIO interface in beide versies up-to-date gehouden worden.

Om dit te faciliteren, kiezen we voor het ontwikkelen en uitrollen van onafhankelijke OOAPI v5 en OOAPI v6 diensten.

Van de Eduhub-gerelateerde diensten die door Jomco worden ontwikkeld, worden "standalone" OOAPI v6 implementaties opgeleverd, zodat de huidige OOAPI v5 "stack" onafhankelijk draait van de nieuwe OOAPI v6. Dit maakt het eenvoudiger om snel te itereren tijdens het ontwikkelen en testen van de nieuwe v6 stack, terwijl de huidige v5 stack stabiel blijft werken.

Omdat we met deze opzet in de codebase van de RIO mapper kunnen afdwingen welke versie van de OOAPI van toepassing is, en we dus geen "als v5 - dan doe X, als v6 - dan doe Y" paden hoeven te implementeren, maakt dit het ook simpeler om de v5 code buiten gebruik te stellen op het moment dat deze niet meer gebruikt wordt.

De door ons ontwikkelde diensten die in de v6 stack komen te draaien zijn de volgende:

- [Eduhub Gateway](https://github.com/suRFnet/eduhub-gateway)

Naar verwachting zal het volstaan om een nieuwe deploy te doen van de bestaande gateway, met aanpassingen aan de configuratie zodat deze overeenkomt met de OOAPI v6 OpenAPI specificatie.

- [Registry Integration](https://github.com/SURFnet/eduhub-registry-integration)

Nieuwe deploy, gekoppeld met Eduhub Gateway, geen code aanpassingen.

- [RIO Mapper](https://github.com/SURFnet/eduhub-rio-mapper/)

Zoals verder beschreven in dit document.

- [Eduhub Validator Service](https://github.com/SURFnet/eduhub-validator-service/)

Nieuwe deploy met OOAPI v6 configuratie, of OOAPI v6 als extra profiel in bestaande instantie. We verwachten weinig tot geen code aanpassingen.


## Functionele wijzigingen in v6 Mapper

In de nieuwe versie van de RIO mapper worden een aantal functionaliteiten gewijzigd of toegevoegd:

### Job create API call geeft een 201 status

Dit is een kleine opschoning van de mapper API.

### Validatie op basis van OpenAPI spec

OOAPI Loader code in mapper valideert OOAPI responses op basis van het RIO profiel zoals dat in de v6 specificatie staat, en weigert niet-valide data te behandelen. De bedoeling is dat, voor zover mogelijk, valide OOAPI responses ook succesvol behandeld worden door de RIO mapper.

## RIO Mapper Code layout & artifacts

Van de RIO Mapper moeten 2 verschillende versies onderhouden worden, wat we vanuit 1 repository en code base gaan doen.

In de codebase kunnen de volgende artifacts gecompileerd worden:

- `eduhub-rio-mapper-v5.jar` - de huidige v5 versie van de RIO Mapper
- `eduhub-rio-mapper-v6.jar` - de nieuwe v6 versie

De broncode voor deze artifacts wordt georganiseerd in 3 directories:

- `src-v5/src/nl/surf/eduhub_rio_mapper/v5`
- `src-v6/src/nl/surf/eduhub_rio_mapper/v6` 
- `src-common/src/nl/surf/eduhub_rio_mapper/common`

Met overeenkomstige test directories `test-v5`, `test-v6` en `test-common`.

De `eduhub-rio-mapper-v5.jar` wordt gemaakt op basis van `src-v5` en `src-common`, `eduhub-rio-mapper-v6.jar` wordt gemaakt op basis van `src-v6` en `src-common`. `src-v5` en `src-v6` mogen niet van elkaar gebruik maken, en `src-common` heeft geen dependencies in `src-v5` of `src-v6`. Bij het bouwen van de jars en het draaien van tests, wordt dmv configuratie in `deps.edn` gezorgd dat alleen de verwachte src & test directories geladen worden.

Dit zorgt voor een duidelijke en afgedwongen scheiding tussen code die afhankelijk is van OOAPI v6 en v5, en geeft toch wel de mogelijkheid om "100% herbruikbare" code te delen tussen beide implementaties.

## Chronologisch stappen plan

###  Prep codebase

We kunnen zo snel mogelijk starten met het opschonen van de v5 en "common" codebases zoals hierboven beschreven. Dit betekent dat we de huidige v5 functionaliteit in zijn geheel hetzelfde houden, maar wel alvast namespaces hernoemen en in de juiste directory trees plaatsen. Dit moet dan gelijk goed te testen zijn (want geen functionele verschillen).

Zodra we v6 functionaliteiten gaan implementeren, wordt dan de `src-v6` en `test-v6` gevuld, zoveel mogelijk op basis van kopieën van de v5 varianten.

Dit werk valt binnen het regulier onderhoud voor Surf eduhub.

### Definitieve ok op OOAPI v6 specificatie

Na eerdere review van de v6 spec is besloten om de link tussen normale Programs en "specification" Programs te laten lopen via attributen in de RIO Consumer van Programs ipv parent/child relaties. De verwachting is dat er verder geen significante wijzigingen meer komen.

### Eduhub Gateway deploy met v6 configuratie

### Registry integratie deploy met v6 configuratie

### Update van eduhub-validator en validator service met v6 profielen

### Prepareren van v6 test data en fixtures

### E2E scenario's vertalen

Hierbij kunnen we de nog niet geïmplementeerde scenario's markeren als "nog te doen".

### Koppelen OpenAPI validatie aan OOAPI Loader

### API change (201 ipv 200)

### Implementatie Create/Update/Delete "specification" Program

Inclusief unit tests en relevante e2e scenario's

### Uitrol in test omgeving

### Implementatie Create/Update/Delete "normale" Program zonder offerings

Inclusief unit tests en relevante e2e scenario's

### Implementatie Create/Update/Delete Program met offerings

Inclusief unit tests en relevante e2e scenario's

### Implementatie Create/Update/Delete Course zonder offerings

Inclusief unit tests en relevante e2e scenario's

### Implementatie Create/Update/Delete Course met offerings

Inclusief unit tests en relevante e2e scenario's

### Mapper CLI

### Uitrol in acceptatie omgeving

Tests met beperkte groep instellingen. Feedback verwerken.

### Uitrol in productie omgeving

Start onderhoudsfase


<!-- Local Variables: -->
<!-- ispell-local-dictionary: "dutch" -->
<!-- End: -->
