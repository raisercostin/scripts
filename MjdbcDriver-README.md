# mjdbc - Multi-Backend JDBC Proxy Driver

**mjdbc** is a lightweight JDBC driver that acts as a proxy in front of multiple real JDBC backends.
It lets you expose one single JDBC URL to tools like DBeaver or apps, while routing queries to different backend datasources.

## Features

- **Single JDBC URL** exposing multiple real JDBC backends
- **Routing strategies**:
  - `hint`   : leading comment `/*ds:<dsKey>*/` in the SQL
  - `regex`  : config-based regex matching on SQL
  - `default`: fallback if no other strategy matches
- **Sticky modes**:
  - `tx`        : backend fixed for the transaction
  - `session`   : backend fixed for the whole connection
  - `statement` : each statement chooses backend independently
- **Passthrough SQL**: native database features preserved
- **Per-connection logging** via `verbosity=0..5`
- **Optional driver auto-loading** via `drivers=<fqcn1>,<fqcn2>,...`
- **DBeaver-friendly**: works with metadata introspection and SQL editor

## Requirements

- Java 17 or later
- JBang for script usage (optional)
- Real JDBC drivers on the classpath (e.g., Postgres, Oracle)

## JDBC URL Specification

```

jdbc:mjdbc:default=<dsKey>;sticky=tx|session|statement;verbosity=0..5;route=hint,regex,default;drivers=<fqcn1>,<fqcn2>,...; <dsKey>=<jdbc-url>;<dsKey>=<jdbc-url>;route.regex.N=<dsKey>:<regex>;

```

### Example

```
jdbc:mjdbc:default=pg;sticky=statement;verbosity=3;route=hint,regex,default;drivers=org.postgresql.Driver,oracle.jdbc.OracleDriver;pg=jdbc:postgresql://localhost:5432/postgres?user=postgres\&password=secret;ora=jdbc:oracle\:thin:@//host:1521/XEPDB1?user=u&assword=p;
route.regex.1=ora:/\bFROM\s+DUAL\b/
```

## Usage as a Command-Line Tool

```
λ jbang https://github.com/raisercostin/scripts/blob/main/MjdbcDriver.java test "jdbc:mjdbc:sticky=statement;verbosity=4;route=hint,regex,default;drivers=org.postgresql.Driver,oracle.jdbc.OracleDriver;default=pg;pg=jdbc:postgresql://localhost:5432/postgres?user=postgres&password=******;ora=jdbc:oracle:thin:@//localhost:1521/SYSTEM?user=system&password=******" "/*ds:pg*/ select version();" "/*ds:ora*/ SELECT VERSION_FULL FROM v$instance" "select version();"

19:31:31.273 [main] INFO mjdbc -- Executing: /*ds:pg*/ select version();
19:31:32.036 [main] INFO mjdbc -- Header: version
19:31:32.037 [main] INFO mjdbc -- Row: PostgreSQL 16.1 (Debian 16.1-1.pgdg120+1) on x86_64-pc-linux-gnu, compiled by gcc (Debian 12.2.0-14) 12.2.0, 64-bit

19:31:32.038 [main] INFO mjdbc -- Executing: /*ds:ora*/ SELECT VERSION_FULL FROM v$instance
19:31:33.118 [main] INFO mjdbc -- Header: VERSION_FULL
19:31:33.120 [main] INFO mjdbc -- Row: 19.21.0.0.0

19:31:33.122 [main] INFO mjdbc -- Executing: select version();
19:31:33.659 [main] INFO mjdbc -- Header: version
19:31:33.661 [main] INFO mjdbc -- Row: PostgreSQL 16.1 (Debian 16.1-1.pgdg120+1) on x86_64-pc-linux-gnu, compiled by gcc (Debian 12.2.0-14) 12.2.0, 64-bit
```

## Usage as a Library

Add as dependency (when published to Maven Central or local Maven repo):

```xml
<dependency>
  <groupId>org.raisercostin.mjdbc</groupId>
  <artifactId>mjdbc</artifactId>
  <version>0.1.2</version>
</dependency>
```

In Java:

```java
Class.forName("org.raisercostin.mjdbc.MjdbcDriver");

Connection conn = DriverManager.getConnection(
  "jdbc:mjdbc:default=pg;pg=jdbc:postgresql://localhost:5432/postgres?user=postgres&password=secret;ora=jdbc:oracle:thin:@//host:1521/XEPDB1?user=u&password=p"
);

try (Statement st = conn.createStatement()) {
  st.execute("/*ds:pg*/ SELECT version()");
  st.execute("/*ds:ora*/ SELECT VERSION_FULL FROM v$instance");
}
```

## Usage in DBeaver

1. **Create new driver**:

   - Driver Name: `mjdbc`
   - Class Name: `org.raisercostin.mjdbc.MjdbcDriver`
   - Add the built `mjdbc.jar` + real JDBC drivers (Postgres, Oracle) to libraries

2. **Create new connection**:

   - Driver: `mjdbc`
   - JDBC URL: see [Example](#example)

3. In SQL Editor:

   ```sql
   /*ds:pg*/  SELECT version();
   /*ds:ora*/ SELECT * FROM v$instance;
   ```

## Development & Releasing

### Local build with JBang

Run directly:

```bash
jbang MjdbcDriver.java
```

Build a fat jar for local testing:

```bash
jbang export portable MjdbcDriver.java --force --output mjdbc.jar
```

Use this jar in DBeaver or other apps.

### Local Maven install

For projects using Maven locally:

```bash
mvn install:install-file \
  -Dfile=mjdbc.jar \
  -DgroupId=org.raisercostin.mjdbc \
  -DartifactId=mjdbc \
  -Dversion=1.0.0 \
  -Dpackaging=jar
```

Then use as a normal dependency in local projects.

### Publish to Maven Central (if desired)

1. Setup a Sonatype OSSRH account and GPG key
2. Add `pom.xml` with coordinates:

   ```xml
   <groupId>org.raisercostin.mjdbc</groupId>
   <artifactId>mjdbc</artifactId>
   <version>1.0.0</version>
   ```

3. Use `mvn deploy` with `nexus-staging-maven-plugin`
4. Close & release staging repo in Sonatype Nexus

## Version History

- 2024-07-10 - Initial release with routing & logging
- 2024-07-11 - Added routing strategy
- 2024-07-11 - Connection pool integration (HikariCP)

## Future Ideas

- Add DataSource for simpler integration
- More routing strategies (e.g., JSON rules)
- Built-in metrics for routed queries
- Optional tracing via OpenTelemetry
