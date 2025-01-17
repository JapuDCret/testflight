/*
 * Copyright 2020 - 2021 Arne Limburg
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package space.testflight;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.stream;
import static java.util.Collections.emptyMap;
import static java.util.stream.Collectors.toMap;
import static org.junit.platform.commons.util.AnnotationUtils.findAnnotation;

import java.io.IOException;
import java.net.URISyntaxException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.flywaydb.core.api.Location;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.migration.JavaMigration;
import org.flywaydb.core.api.resource.LoadableResource;
import org.flywaydb.core.internal.database.mysql.MySQLParser;
import org.flywaydb.core.internal.database.postgresql.PostgreSQLParser;
import org.flywaydb.core.internal.jdbc.JdbcTemplate;
import org.flywaydb.core.internal.parser.Parser;
import org.flywaydb.core.internal.parser.ParsingContext;
import org.flywaydb.core.internal.resource.classpath.ClassPathResource;
import org.flywaydb.core.internal.resource.filesystem.FileSystemResource;
import org.flywaydb.core.internal.scanner.LocationScannerCache;
import org.flywaydb.core.internal.scanner.ResourceNameCache;
import org.flywaydb.core.internal.scanner.classpath.ClassPathScanner;
import org.flywaydb.core.internal.scanner.filesystem.FileSystemScanner;
import org.flywaydb.core.internal.sqlscript.SqlStatementIterator;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ExtensionContext.Store;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.TestInstancePostProcessor;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.JdbcDatabaseContainer;

import com.github.dockerjava.api.model.Image;

import space.testflight.Flyway.DatabaseType;

public class FlywayExtension implements BeforeAllCallback, BeforeEachCallback, BeforeTestExecutionCallback,
  AfterTestExecutionCallback, AfterEachCallback, AfterAllCallback, TestInstancePostProcessor, ParameterResolver {

  public static final String TESTFLIGHT_PREFIX = "testflight-";
  static final String JDBC_URL = "space.testflight.jdbc.url";
  static final String JDBC_USERNAME = "space.testflight.jdbc.username";
  static final String JDBC_PASSWORD = "space.testflight.jdbc.password";
  static final String MIGRATION_TAG = "migration.tag";
  static final String STORE_IMAGE = "image";
  static final String STORE_CONTAINER = "container";
  private static final String JDBC_URL_PROPERTY = "space.testflight.jdbc.url.property";
  private static final String JDBC_USERNAME_PROPERTY = "space.testflight.jdbc.username.property";
  private static final String JDBC_PASSWORD_PROPERTY = "space.testflight.jdbc.password.property";
  private static final String JDBC_PORT = "jdbc.port";

  private final ResourceInjector injector = new ResourceInjector();
  private final DatabaseContainerFactory containerFactory = new DatabaseContainerFactory();

  @Override
  public void beforeAll(ExtensionContext context) throws Exception {
    initialize(context);

    if (getDatabaseInstance(context) == Flyway.DatabaseInstanceScope.PER_TEST_CLASS) {
      startDatabase(context, false);
    }
  }

  @Override
  public void beforeEach(ExtensionContext context) throws Exception {
    if (getDatabaseInstance(context) == Flyway.DatabaseInstanceScope.PER_TEST_METHOD) {
      startDatabase(context, true);
    }
  }

  @Override
  public void beforeTestExecution(ExtensionContext context) throws Exception {
    if (getDatabaseInstance(context) == Flyway.DatabaseInstanceScope.PER_TEST_EXECUTION) {
      startDatabase(context, true);
    }
  }

  @Override
  public void afterTestExecution(ExtensionContext context) throws Exception {
    if (getDatabaseInstance(context) == Flyway.DatabaseInstanceScope.PER_TEST_EXECUTION) {
      teardownDb(context, true);
    }
  }

  @Override
  public void afterEach(ExtensionContext context) throws Exception {
    if (getDatabaseInstance(context) == Flyway.DatabaseInstanceScope.PER_TEST_METHOD) {
      teardownDb(context, true);
    }
  }

  @Override
  public void afterAll(ExtensionContext context) throws Exception {
    if (getDatabaseInstance(context) == Flyway.DatabaseInstanceScope.PER_TEST_CLASS) {
      teardownDb(context, false);
    }
  }

  @Override
  public void postProcessTestInstance(Object testInstance, ExtensionContext context) throws Exception {
    injector.inject(testInstance, context);
  }

  @Override
  public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
      throws ParameterResolutionException {
    return injector.canInject(parameterContext.getParameter());
  }

  @Override
  public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
      throws ParameterResolutionException {
    try {
      return injector.getInjectedValue(parameterContext.getParameter(), extensionContext);
    } catch (ReflectiveOperationException e) {
      throw new ParameterResolutionException(e.getMessage(), e);
    }
  }

  private void startDatabase(ExtensionContext context, boolean useMethodLevelStore) {
    String tag = getClassStore(context).get(MIGRATION_TAG, String.class);
    JdbcDatabaseContainer<?> container = getGlobalStore(context, tag).get(STORE_CONTAINER, JdbcDatabaseContainer.class);
    if (!container.isRunning()) {
      container = createContainer(context, StartupType.FAST);
      container.start();
    }

    if (useMethodLevelStore) {
      getMethodStore(context).put(STORE_CONTAINER, container);
    } else {
      getClassStore(context).put(STORE_CONTAINER, container);
    }
  }

  private void teardownDb(ExtensionContext context, boolean useMethodLevelStore) throws Exception {
    if (useMethodLevelStore) {
      getMethodStore(context).get(STORE_CONTAINER, AutoCloseable.class).close();
    } else {
      getClassStore(context).get(STORE_CONTAINER, AutoCloseable.class).close();
    }
  }

  private <C extends JdbcDatabaseContainer<C> & TaggableContainer> void initialize(ExtensionContext context) throws Exception {
    Optional<Flyway> configuration = findAnnotation(context.getTestClass(), Flyway.class);
    org.flywaydb.core.Flyway dryway = org.flywaydb.core.Flyway.configure()
      .configuration(configuration
      .map(Flyway::configuration)
      .map(properties -> stream(properties).collect(toMap(ConfigProperty::key, ConfigProperty::value)))
      .orElse(emptyMap()))
      .load();
    Optional<String> currentMigrationTarget = getCurrentMigrationTarget(dryway);
    List<LoadableResource> loadableTestDataResources = getTestDataScriptResources(configuration);
    int testDataTagSuffix = getTestDataTagSuffix(loadableTestDataResources);
    String tagName = TESTFLIGHT_PREFIX + currentMigrationTarget.orElse("") + testDataTagSuffix;

    Store globalStore = getGlobalStore(context, tagName);
    Store classStore = getClassStore(context);
    DatabaseType type = configuration.map(Flyway::database).orElse(DatabaseType.POSTGRESQL);
    String image = type.getImage(tagName);
    classStore.put(MIGRATION_TAG, tagName);

    C container;
    if (!existsImage(image)) {
      container = createContainer(context, StartupType.SLOW);
      container.start();
      org.flywaydb.core.Flyway flyway = org.flywaydb.core.Flyway.configure()
        .configuration(dryway.getConfiguration())
        .dataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword())
        .load();

      prefillDatabase(container, type, loadableTestDataResources, flyway);
      container.tag(tagName);
      globalStore.put(STORE_IMAGE, image);
    } else {
      globalStore.put(STORE_IMAGE, image);
      container = createContainer(context, StartupType.FAST);
      container.start();
    }
    globalStore.put(JDBC_URL, container.getJdbcUrl());
    globalStore.put(JDBC_USERNAME, container.getUsername());
    globalStore.put(JDBC_PASSWORD, container.getPassword());
    globalStore.put(JDBC_PORT, container.getMappedPort(container.getContainerPort()));
    globalStore.put(STORE_CONTAINER, container);
    setSystemProperties(configuration, globalStore);
  }

  private void prefillDatabase(
    JdbcDatabaseContainer<?> container,
    DatabaseType type,
    List<LoadableResource> loadableTestDataResources,
    org.flywaydb.core.Flyway flyway) throws SQLException {
    flyway.migrate();

    Configuration flywayConfiguration = flyway.getConfiguration();
    ParsingContext parsingContext = new ParsingContext();
    JdbcTemplate jdbcTemplate = new JdbcTemplate(flywayConfiguration.getDataSource().getConnection());
    Parser parser;
    switch (type) {
      case POSTGRESQL:
        parser = new PostgreSQLParser(flywayConfiguration, parsingContext);
        break;
      case MYSQL:
        parser = new MySQLParser(flywayConfiguration, parsingContext);
        break;
      default:
        throw new IllegalStateException("Unsupported database type " + type);
    }

    for (LoadableResource testDataScript : loadableTestDataResources) {
      SqlStatementIterator parse = parser.parse(testDataScript);
      parse.forEachRemaining(p -> p.execute(jdbcTemplate));
    }
  }

  private List<LoadableResource> getTestDataScriptResources(Optional<Flyway> configuration) {
    List<LoadableResource> loadableResources = new ArrayList<>();
    if (configuration.isPresent()) {
      String[] testDataScripts = configuration.get().testDataScripts();
      for (String testDataScript : testDataScripts) {
        Location scriptLocation = new Location(testDataScript);
        if (scriptLocation.isClassPath()) {
          LoadableResource loadableResource = new ClassPathResource(null, testDataScript, this.getClass().getClassLoader(), UTF_8);
          loadableResources.add(loadableResource);
        } else if (scriptLocation.isFileSystem()) {
          LoadableResource loadableResource = new FileSystemResource(null, scriptLocation.getPath(), UTF_8, false);
          loadableResources.add(loadableResource);
        } else {
          throw new IllegalStateException("Unsupported test data location " + scriptLocation);
        }
      }
    }

    return loadableResources;
  }

  private int getTestDataTagSuffix(List<LoadableResource> loadableTestDataResources) {
    StringBuilder stringBuilder = new StringBuilder();
    for (LoadableResource loadableTestDataResource : loadableTestDataResources) {
      stringBuilder.append(loadableTestDataResource.getFilename());
    }

    return stringBuilder.toString().hashCode();
  }

  private void setSystemProperties(Optional<Flyway> configuration, Store globalStore) {
    String urlPropertyName = configuration.map(Flyway::configuration)
      .map(Arrays::stream)
      .flatMap(config -> config.filter(entry -> entry.key().equals(JDBC_URL_PROPERTY)).findAny())
      .map(ConfigProperty::value)
      .orElse(JDBC_URL);
    String userPropertyName = configuration.map(Flyway::configuration)
      .map(Arrays::stream)
      .flatMap(config -> config.filter(entry -> entry.key().equals(JDBC_USERNAME_PROPERTY)).findAny())
      .map(ConfigProperty::value)
      .orElse(JDBC_USERNAME);
    String passwordPropertyName = configuration.map(Flyway::configuration)
      .map(Arrays::stream)
      .flatMap(config -> config.filter(entry -> entry.key().equals(JDBC_PASSWORD_PROPERTY)).findAny())
      .map(ConfigProperty::value)
      .orElse(JDBC_PASSWORD);
    System.setProperty(urlPropertyName, globalStore.get(JDBC_URL, String.class));
    System.setProperty(userPropertyName, globalStore.get(JDBC_USERNAME, String.class));
    System.setProperty(passwordPropertyName, globalStore.get(JDBC_PASSWORD, String.class));
  }

  private Optional<String> getCurrentMigrationTarget(org.flywaydb.core.Flyway dryway) throws URISyntaxException, IOException {
    Configuration configuration = dryway.getConfiguration();
    Location[] locations = configuration.getLocations();
    List<LoadableResource> migrations = new ArrayList<>();

    FileSystemScanner fileSystemScanner = new FileSystemScanner(configuration.getEncoding(), false, false, false);
    for (Location location : locations) {
      if (location.isClassPath()) {
        ClassPathScanner<JavaMigration> scanner = new ClassPathScanner<JavaMigration>(
          JavaMigration.class,
          configuration.getClassLoader(),
          configuration.getEncoding(),
          location,
          new ResourceNameCache(),
          new LocationScannerCache(),
          false);
        migrations.addAll(scanner.scanForResources());
      } else if (location.isFileSystem()) {
        migrations.addAll(fileSystemScanner.scanForResources(location));
      } else {
        throw new IllegalStateException("Unsupported location " + location);
      }
    }

    Optional<LoadableResource> latestMigration = migrations.stream().sorted(Comparator.reverseOrder()).findFirst();
    return latestMigration.map(p -> p.getFilename().toString().split("__")[0].replace(".", "_"));
  }

  static ExtensionContext.Store getGlobalStore(ExtensionContext context) {
    Store classStore = getClassStore(context);
    return getGlobalStore(context, classStore.get(FlywayExtension.MIGRATION_TAG, String.class));
  }

  static ExtensionContext.Store getGlobalStore(ExtensionContext context, String tag) {
    return context.getRoot().getStore(Namespace.create(FlywayExtension.class, tag));
  }

  static ExtensionContext.Store getClassStore(ExtensionContext context) {
    return context.getStore(Namespace.create(context.getRequiredTestClass()));
  }

  private ExtensionContext.Store getMethodStore(ExtensionContext context) {
    return context.getStore(Namespace.create(getClass(), context.getRequiredTestMethod()));
  }

  private <C extends JdbcDatabaseContainer<C> & TaggableContainer> C createContainer(ExtensionContext context, StartupType startup) {
    Optional<Flyway> configuration = findAnnotation(context.getTestClass(), Flyway.class);
    C container;
    if (!configuration.isPresent()) {
      container = containerFactory.createDatabaseContainer(context, DatabaseType.POSTGRESQL, startup);
    } else {
      Flyway flywayConfiguration = configuration.get();
      container = containerFactory.createDatabaseContainer(context, flywayConfiguration.database(), startup);
    }
    String tag = getClassStore(context).get(MIGRATION_TAG, String.class);
    Optional.ofNullable(getGlobalStore(context, tag).get(JDBC_PORT, Integer.class))
      .ifPresent(hostPort -> container.addFixedPort(hostPort, container.getContainerPort()));
    return container;
  }

  private boolean existsImage(String imageName) {
    List<Image> imageList = DockerClientFactory.lazyClient()
      .listImagesCmd()
      .exec();

    return imageList.stream()
      .map(i -> i.getRepoTags())
      .filter(Objects::nonNull)
      .flatMap(Arrays::stream)
      .anyMatch(t -> t.equals(imageName));

  }

  private Flyway.DatabaseInstanceScope getDatabaseInstance(ExtensionContext context) {
    return findAnnotation(context.getTestClass(), Flyway.class)
      .map(Flyway::databaseInstance)
      .orElse(Flyway.DatabaseInstanceScope.PER_TEST_METHOD);
  }

  enum StartupType {
    SLOW, FAST;
  }
}
