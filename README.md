# Embedded GraphDB loader

Run the application with `sbt run`. At startup it reads the TriG file configured by
`graphdb.data-file`, loads its statements into the persistent repository, then starts
the HTTP server on `http.port` (9999 by default).

Edit [application.conf](src/main/resources/application.conf) to change the repository
directory, input file, or port. Relative paths are resolved from the process working
directory. Edit [seed.trig](data/seed.trig) to add or change input RDF. TriG supports
both ordinary triples and triples inside named graph blocks:

```trig
<http://example.org/graphs/team-a> {
  <http://example.org/person/3> <http://schema.org/name> "Carol" .
}
```

The import is transactional. Re-running it does not create duplicate RDF statements.
It adds data to the existing repository; removing a statement from the input file
does not remove an already stored statement. Stop the server before changing the
repository directory or opening its files with another GraphDB process.

`GET /query` accepts `predicate` plus exactly one of `subject` or `object`:

```text
http://localhost:9999/query?subject=http%3A%2F%2Fexample.org%2Fperson%2F3&predicate=http%3A%2F%2Fschema.org%2Fname
http://localhost:9999/query?object=http%3A%2F%2Fschema.org%2FPerson&predicate=http%3A%2F%2Fwww.w3.org%2F1999%2F02%2F22-rdf-syntax-ns%23type
```

Responses are JSON arrays. IRI objects are returned as IRIs; literal objects use
RDF4J's literal notation, including datatype or language where present. `subject`,
`predicate`, and optional `graph` must be absolute IRIs. For `object`, an absolute
IRI is treated as an IRI and other text as a plain literal. Use `objectType=iri` or
`objectType=literal` to choose explicitly. Add `graph=<URL-encoded graph IRI>` to
search only that named graph; without it, queries include all contexts. URL-encode
query parameter values, especially `#` in RDF vocabulary IRIs.
