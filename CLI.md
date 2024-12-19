# SURFeduhub RIO mapper Command Line Interface

## Prerequisites - keystores

There must be a Java keystore available containing the client
certificate needed to access the RIO API.  See
`dev/create-keystore.sh` on how to create a keystore, given the
private key and the certificate. 

The application also depends on a JAVA truststore for validating the
certificate of the RIO API itself.  A truststore.jks is provided at
the root of this project. See `dev/create-truststore.sh` on how
to create a truststore yourself.

## Running commands

Commands can be run in development mode, from leiningen, or from the compiled uberjar.

In development mode, commands can be executed directly from the source repository using `leiningen`.  Leiningen should be installed; https://leiningen.org/

Mapper commands in leiningen take the form:

```sh
lein mapper COMMAND [ARGS]
```

For instance

```sh
lein mapper upsert uni-nl course 123e4567-e89b-12d3-a456-426655440000
```


In production mode, the commands can be run as arguments to the
compiled jar:

```sh
java -jar target/eduhub-rio-mapper.jar COMMAND [ARGS]
````

For instance

```sh
java -jar target/edhuhub-rio-mapper.jar upsert uni-id course 123e4567-e89b-12d3-a456-426655440000
```

## Commands

### upsert

Updates or inserts an "opleidingseenheid" or "aangeboden opleiding,
specified by the OOAPI endpoint, type and ID.  An example:

```sh
lein mapper upsert uni-id course 123e4567-e89b-12d3-a456-426655440000
```

### delete

Removes an "opleidingseenheid" or "aangeboden opleiding, specified by
the OOAPI endpoint, type and ID.  An example:

```sh
lein mapper delete uni-id course 123e4567-e89b-12d3-a456-426655440000
```

### delete-by-code

Removes an "opleidingseenheid", specified by the OOAPI endpoint, type and RIO opleidingscode.  An example:

```sh
lein mapper delete-by-code uni-id education-specification 1234O1234
```

### show

The `show` command retrieves data from OOAPI. The following entities are supported:

#### courses

Example:

```sh
lein mapper show uni-id courses
```

#### course

Example:

```sh
lein mapper show uni-id course 123e4567-e89b-12d3-a456-426655440000
```

#### programs

Example:

```sh
lein mapper show uni-id programs
```

#### program

Example:

```sh
lein mapper show uni-id program 123e4567-e89b-12d3-a456-426655440000
```

#### education-specifications

Example:

```sh
lein mapper show uni-id education-specifications
```

#### education-specification

Example:

```sh
lein mapper show uni-id education-specification 123e4567-e89b-12d3-a456-426655440000
```

### get

The `get` command retrieves data from RIO. The following actions are
supported:

#### aangebodenOpleiding

This action retrieves the "aangeboden opleiding" based on its course
ID or program ID (the rio code, not the ooapi id / eigen sleutel)

Example:

```sh
lein mapper get uni-id aangebodenOpleiding 123e4567-e89b-12d3-a456-426655440000
```

An `xml:` prefix can be added to specify that the output should show the XML response from RIO. Example:

```sh
lein mapper get uni-id xml:aangebodenOpleiding 123e4567-e89b-12d3-a456-426655440000
```

#### opleidingseenheid

This action retrieves the "opleidingseenheid" based on its opleidingeenheidcode.

Example:

```sh
lein mapper get uni-id opleidingseenheid 1015O5036
```

An `xml:` prefix can be added to specify that the output should show the XML response from RIO. Example: 

```sh
lein mapper get uni-id xml:opleidingseenheid 1015O5036
```

#### opleidingseenhedenVanOrganisatie

This action retrieves a page of "opleidingseenheden" for a specific
"onderwijsbestuur". Pages start counting at zero, not one. 

Example:

```sh
lein mapper get uni-id opleidingseenhedenVanOrganisatie 100B490
```

An optional page argument can be passed. Example:

```sh
lein mapper get uni-id opleidingseenhedenVanOrganisatie 100B490 4
```

An `xml:` prefix can be added to specify that the output should show the XML response from RIO.

```sh
lein mapper get uni-id xml:aangebodenOpleidingenVanOrganisatie 110A133 2
```


#### aangebodenOpleidingenVanOrganisatie

This action retrieves a page of "aangeboden opleidingen" for a 
specific organization, specified by a "onderwijsaanbiedercode".

```sh
lein mapper get uni-id aangebodenOpleidingenVanOrganisatie 110A133
```

An optional page argument can be passed.

Example:

```sh
lein mapper get uni-id aangebodenOpleidingenVanOrganisatie 110A133 2
```

An `xml:` prefix can be added to specify that the output should show the XML response from RIO.

```sh
lein mapper get uni-id xml:aangebodenOpleidingenVanOrganisatie 110A133 2
```

### resolve

This action retrieves the `opleidingeenheidscode` or `aangebodenopleidingscode` based on type and
the key that OOAPI uses as primary key for the OOAPI object. 
Type is one of: `course`, `program`, `education-specification`.

Example:

```sh
lein mapper resolve uni-id course 123e4567-e89b-12d3-a456-426655440000
```

### dry-run-upsert

This action simulates an upsert of an OOAPI object and returns a diff of the fields that such an upsert would change in RIO.

Example:

```sh
lein mapper dry-run-upsert uni-id course 4c358c84-dfc3-4a30-874e-0b70db15638a
```

### link

This action changes the OOAPI id of an object in RIO specified by a given RIO id and a type.

Example:

```sh
lein mapper link uni-id rio-id course ooapi-id
```

### unlink

This action removes the OOAPI id of an object in rio specified by a given RIO id and a type. 
The RIO object is no longer linked to an OOAPI-object.

Example:

```sh
lein mapper unlink uni-id rio-id course
```

### serve-api

This action starts the API HTTP server at the configured port and
hostname (default localhost, port 8080).

### worker

This action starts a worker.

### test-rio

This action tests if the RIO queue is up or down. If the queue is up, it will exit with code 0.
If it is down, it will print a warning and exit with code -1.

Example:

```sh
lein mapper test-rio uni-id
```
