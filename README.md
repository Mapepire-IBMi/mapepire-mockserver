# Mapepire Mock Server

> [!WARNING]
> **This project is experimental.** It is not a supported Mapepire component. Its behavior, command-line
> options, rule format, and Java API may change or break between versions without notice. Use it for development
> and testing only, never in production, and don't rely on it matching the real Mapepire server in every detail.

A standalone, self-contained mock of the [Mapepire server](https://github.com/Mapepire-IBMi/mapepire-server).
It speaks the [Mapepire WebSocket protocol](https://github.com/Mapepire-IBMi/mapepire-protocol) and answers
requests from a set of configurable rules instead of a real IBM i system and Db2 database.

Use it to develop and test Mapepire clients (for example [mapepire-java](https://github.com/Mapepire-IBMi/mapepire-java))
on any machine, in CI, or anywhere an IBM i is not available.

- Listens on port **7892** by default, at `wss://<host>:7892/db/`.
- Uses TLS by default, with a bundled self-signed certificate for `localhost`. Plain `ws://` is available with `--unsecure`.
- Requires HTTP Basic authentication on the WebSocket upgrade, as the real server does. Only two built-in accounts are accepted (see [Authentication](#authentication)).
- Supports every request type the real server handles (see [Protocol coverage](#protocol-coverage)).
- Built-in sample data (`SAMPLE.DEPARTMENT`, `SYSIBM.SYSDUMMY1`, and more), plus your own rules from a JSON file or from code.
- A real in-memory SQL database ([SQLite](#sqlite-database)) for statements no rule answers, so tests can create schemas and tables, insert rows, and query them.
- Java 8 or later. The only runtime dependencies are Gson, Java-WebSocket (and its no-op SLF4J binding), and the SQLite JDBC driver, all packaged into one jar.

## Building

```sh
mvn package
```

This compiles for Java 8, runs all tests, and produces
`target/mapepire-mockserver-<version>-jar-with-dependencies.jar`, a single runnable jar with every dependency
included.

To skip the tests that use the Mapepire Java client and run only the standalone ones:

```sh
mvn package -DexcludedGroups=java-client
```

## Running

```sh
java -jar target/mapepire-mockserver-<version>-jar-with-dependencies.jar
```

```text
Options:
  -p, --port <port>              Port to listen on (default: 7892, or the PORT environment variable)
      --host <address>           Address to bind to (default: 0.0.0.0)
      --unsecure                 Serve plain ws:// instead of wss:// (also: MP_UNSECURE=true)
      --keystore <file>          Keystore holding the TLS certificate (default: bundled self-signed certificate)
      --keystore-password <pw>   Keystore password (or the MP_MOCK_KEYSTORE_PASSWORD environment variable)
      --keystore-type <type>     Keystore type (default: PKCS12)
  -r, --rules <file>             JSON file of SQL and CL rules (see below)
  -v, --verbose                  Echo all trace records to standard output
      --version                  Print the version and exit
  -h, --help                     Print this help and exit
```

### Authentication

The mock server accepts exactly two accounts:

| User profile | Password |
|---|---|
| `MOCK` | `mockingbird` |
| `MOCK2` | `mockeroni` |

As on IBM i, user profiles are **not** case-sensitive (`mock` and `Mock2` work), but passwords **are**
(`Mockingbird` is rejected). Any other credentials, a missing `Authorization` header, or a scheme other than
Basic cause the WebSocket upgrade to be refused. The accounts can't be changed, and in code they are available
as `MockServerConfig.MOCK_USER`, `MOCK_PASSWORD`, `MOCK2_USER`, and `MOCK2_PASSWORD`.

### Connecting a client

The bundled certificate is self-signed, so a client must either skip certificate verification or trust the
certificate explicitly. The certificate is at
[`src/main/resources/io/github/mapepire_ibmi/mockserver/mockserver-cert.pem`](src/main/resources/io/github/mapepire_ibmi/mockserver/mockserver-cert.pem)
and is valid for `localhost`, `127.0.0.1`, and `::1`. With mapepire-java:

```java
// Skip verification
DaemonServer creds = new DaemonServer("localhost", 7892, "MOCK", "mockingbird", false);

// Or verify against the mock server's certificate
DaemonServer verified = new DaemonServer("localhost", 7892, "MOCK", "mockingbird", true, pemText);

SqlJob job = new SqlJob();
job.connect(creds).get();
QueryResult<Object> result = job.execute("SELECT * FROM SAMPLE.DEPARTMENT").get();
```

To use your own certificate, pass `--keystore`, `--keystore-password`, and, if it isn't PKCS12, `--keystore-type`.

## Embedding in tests

The server can run inside a JVM, for example in a JUnit test:

```java
MockServerConfig config = new MockServerConfig()
        .setPort(0);                                 // pick a free port
try (MockServer server = new MockServer(config)) {
    server.getDatabase().addSqlRule(SqlRule.updateCount("delete from myapp\\.orders\\b.*", 3));
    server.getDatabase().addSqlRule(SqlRule.error("select \\* from myapp\\.secret", "[SQL0551] Not authorized.", "42501", -551));
    server.getDatabase().addSqlRule(SqlRule.updateCount("call myapp\\.slow", 0).withDelay(5000));
    server.start();

    // connect a client to server.getUri(), e.g. wss://localhost:54321/db/, as MOCK / mockingbird
}
```

You can add rules while the server is running. For testing without any network at all, `MockSession` is the
protocol engine on its own. It takes request text and returns reply text.

## Rules

Each SQL statement and CL command is answered by the first rule that matches it. Rules you add come first, in
the order you added them, followed by the built-in rules.

A rule's `pattern` is a Java regular expression. It is matched against the **entire** statement,
**case-insensitively**, with `.` also matching line breaks. Surrounding whitespace and trailing semicolons are
removed before matching. Remember to escape regex metacharacters such as `*`, `.`, `(` and `?`.

```json
{
  "include_defaults": true,
  "sql": [
    {
      "pattern": "select\\s+id,\\s*name\\s+from\\s+myapp\\.customers",
      "columns": [
        { "name": "ID", "type": "INTEGER", "nullable": 0 },
        { "name": "NAME", "type": "VARCHAR", "precision": 50 }
      ],
      "rows": [ [1, "Alice"], { "ID": 2, "NAME": "Bob" } ]
    },
    { "pattern": "select name from myapp\\.customers where id = \\?", "parameters": [1],
      "columns": [ { "name": "NAME" } ], "rows": [ ["Alice"] ] },
    { "pattern": "insert\\s+into\\s+myapp\\.customers\\b.*", "update_count": 1 },
    { "pattern": "drop\\s+table\\s+.*", "error": { "message": "[SQL0551] Not authorized.", "sql_state": "42501", "sql_rc": -551 } }
  ],
  "cl": [
    { "pattern": "dltobj\\b.*", "success": false,
      "messages": [ { "id": "CPF2105", "type": "ESCAPE", "severity": 40, "text": "Object not found." } ] }
  ]
}
```

A complete example is in [`examples/rules.json`](examples/rules.json).

### SQL rule fields

| Field | Description |
|---|---|
| `pattern` | Required. The regular expression the statement must match. |
| `columns` | Makes the rule return a result set. Each column needs a `name`. Optional fields are `type` (default `VARCHAR`), `precision`, `scale`, `display_size`, `label`, `nullable` (0, 1 or 2; default 1), `table`, `autoIncrement`, `readOnly`, and `writeable`. |
| `rows` | Rows for a result set. Each row is an array of values in column order, or an object keyed by column name (missing columns become `null`). Values are returned with the JSON types you give them. |
| `update_count` | For rules without `columns`: the number of rows to report as affected (default 0). |
| `error` | Makes matching statements fail. Takes `message`, `sql_state`, and `sql_rc`. |
| `parameters` | Restricts the rule to prepared executions whose `parameters` array is exactly this array. |
| `output_parms` | The `output_parms` array to return from prepared executions, for stored procedure `CALL`s. |
| `delay_ms` | Wait this many milliseconds before replying. Useful for testing client timeouts. |

When no rule matches, the statement is run against the mock server's [SQLite database](#sqlite-database). For
example, a query on a table that doesn't exist fails with SQL0204 (`42704`, object not found), and a statement
that isn't valid SQL fails with SQL0104 (`42601`, token not valid).

### CL rule fields

| Field | Description |
|---|---|
| `pattern` | Required. The regular expression the command must match. |
| `success` | Whether the command succeeds (default `true`). |
| `messages` | The messages to report. Each one uses the real server's message fields: `id`, `type`, `severity`, `text`, `help`, `time`, `messageLibrary`, `messageFile`, `sendingProgram`, `sendingInstruction`, `receivingProgram`, and `receivingInstruction`. Any you leave out get defaults. |
| `delay_ms` | Wait this many milliseconds before replying. |

As on the real server, a failed command's error text comes from its last `ESCAPE` message, ignoring `CPF0006`.

### Visual Explain

The `dove` request returns the result set in the top-level `visual_explain` object, which has `columns` and
`rows` like a SQL rule. When `run` is true, the statement's own results are returned as well.

### Built-in rules

These are in [`default-rules.json`](src/main/resources/io/github/mapepire_ibmi/mockserver/default-rules.json).
Set `"include_defaults": false` in a rules file to turn them off.

| Statement or command | Response |
|---|---|
| `SELECT * FROM SAMPLE.DEPARTMENT` | The 14 rows of the Db2 sample `DEPARTMENT` table |
| `SELECT * FROM SAMPLE.DEPARTMENT WHERE ADMRDEPT = ?` | 5 rows for `A00`, 7 for `E01`, none otherwise |
| `INSERT INTO` / `UPDATE` / `DELETE FROM SAMPLE.DEPARTMENT ...` | Update count 1 |
| `SELECT * FROM SYSIBM.SYSDUMMY1` | One row, `IBMREQD = 'Y'` |
| The pending-transaction query used by mapepire-java | `THECOUNT = 0` |
| `CRTLIB ...` | Success, `CPC2102 Library created.` |
| `DLTLIB NOSUCHLIB` | Failure, `CPF2110 Library NOSUCHLIB not found.` |
| `BADCMD` / `NOTACMD` | Failure, `CPD0030` and `CPF0006` |
| Any other CL command | Success, one completion message |

## SQLite database

Every mock server has a real SQL database behind it. SQL statements that match **no rule** are run against an
in-memory [SQLite](https://www.sqlite.org/) database, so tests can create schemas and tables, insert rows, and
query them with ordinary SQL. Rules always come first, so the built-in sample data (`SYSIBM.SYSDUMMY1`,
`SAMPLE.DEPARTMENT`, and so on) and your own rules work as before.

```sql
CREATE SCHEMA MYLIB
CREATE TABLE MYLIB.ITEMS (ID INTEGER PRIMARY KEY, NAME VARCHAR(50))
INSERT INTO MYLIB.ITEMS VALUES (?, ?)    -- prepared, single or batch
SELECT * FROM MYLIB.ITEMS ORDER BY ID     -- fetched in blocks with sqlmore
```

How it behaves:

- **Nothing is persisted.** The database exists only in memory. Nothing is written to disk, and all data is gone
  when the mock server process ends.
- Each `MockDatabase` (normally one per server) has its own SQLite database, shared by every connection to that
  server, so all of its clients see the same data. Statements run one at a time.
- A `QTEMP` schema always exists, as it does on IBM i. Unlike on IBM i, it's shared by all connections.
- SQLite has no schemas, so `CREATE SCHEMA name` (or `CREATE COLLECTION`) attaches a new, empty in-memory
  database under that name, and `name.table` then works as it does on Db2. `DROP SCHEMA name` (with optional
  `CASCADE` or `RESTRICT`) discards it and its tables.
- `COMMIT` and `ROLLBACK` succeed when no transaction is active, and an empty statement fails with SQL0104.
- Every other statement is passed to SQLite unchanged, so it must be valid SQLite SQL. Common Db2 column types
  such as `INTEGER`, `VARCHAR(n)`, `DECIMAL(p, s)`, and `TIMESTAMP` are accepted, but Db2-only syntax (such as
  `VALUES 1` without parentheses) and catalog views (`QSYS2`, `SYSIBM` beyond the built-in rules) are not.
- Parameter markers (`?`) and batch execution work as they do with rules.
- SQLite errors are reported with the closest Db2 SQLSTATE and SQL code: `42704`/SQL0204 for a missing table or
  schema, `42703`/SQL0206 for a missing column, `42710`/SQL0601 for an object that already exists,
  `23505`/SQL0803 for a duplicate key, `23502`/SQL0407 for a null in a `NOT NULL` column, and `42601`/SQL0104
  for a syntax error. Anything else is reported as `HY000`.
- Column names are reported as SQLite returns them (as written in the table definition or select list), not
  folded to upper case as Db2 does.

## Protocol coverage

Every request type the real server dispatches is supported: `connect`, `sql`, `prepare_sql`,
`prepare_sql_execute`, `execute`, `sqlmore`, `sqlclose`, `cl`, `rccl`, `dove`, `ping`, `getdbjob`,
`getversion`, `setconfig`, `gettracedata`, `getcldoc`, `getcmdxml`, and `exit`. It also handles unparseable,
incomplete, and unknown requests the same way the server does.

Replies match the real server: the same field names and field order, nulls serialized explicitly, the request
`id` becoming the cursor handle, `rows` defaulting to 1000, terse mode, batch execution, and continuation errors
(`Correlation ID not specified`, `invalid correlation ID`).

Where the protocol document and the real server's code disagree, the mock follows the server, because that is
what clients actually receive:

- `cl` returns its job log in `data` as message objects (`id`, `text`, `type`, ...), the same shape as `rccl`.
- `getversion` returns `build_date` and `version`.

Intentional differences from the real server:

- Statements that no rule answers are run by [SQLite](#sqlite-database), not Db2, so Db2-specific SQL and catalog views aren't available.
- Credentials are checked against the two fixed mock accounts, not against IBM i user profiles.
- Each connection gets a made-up job name like `100000/QUSER/QZDASOINIT`. A new `connect` starts a new job and discards that connection's open statements.
- Tracing is kept in memory only. With `tracedest` set to `FILE`, the reported path is a placeholder and nothing is written to disk.
- `getcldoc` and `getcmdxml` return placeholder documents.
- The HTTP endpoints next to the WebSocket endpoint (version, blob download) are not provided.
- A refused WebSocket upgrade (bad credentials, or a path other than `/db/`) gets `HTTP 404 WebSocket Upgrade Failure` (from the Java-WebSocket library), not the real server's `403`.
- Requests from one connection are processed one at a time, in order.

## Tests

- `src/test/java/io/github/mapepire_ibmi/mockserver/`: standalone tests. They use only the mock server and a bare WebSocket client, with no external services.
- `src/test/java/io/github/mapepire_ibmi/mockserver/client/`: tests that drive the mock server through the official Mapepire Java client ([`mapepire-sdk`](https://central.sonatype.com/artifact/io.github.mapepire-ibmi/mapepire-sdk)). They are tagged `java-client`.

GitHub Actions ([`.github/workflows/jar_dist_ci.yaml`](.github/workflows/jar_dist_ci.yaml)) runs on every pull
request and every commit to `main`. On Java 8, it runs all the tests (standalone and Java client), uploads the
test reports as a `test-reports` artifact (even when tests fail), then builds and uploads the runnable jar.

## Coding conventions

The Java code follows the
[mapepire-server coding conventions](https://github.com/Mapepire-IBMi/mapepire-server/blob/main/CODING_CONVENTIONS_JAVA.md).

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
