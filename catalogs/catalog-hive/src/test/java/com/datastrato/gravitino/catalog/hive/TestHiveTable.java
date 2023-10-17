/*
 * Copyright 2023 Datastrato.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog.hive;

import static com.datastrato.gravitino.catalog.BaseCatalog.CATALOG_BYPASS_PREFIX;
import static com.datastrato.gravitino.catalog.hive.HiveCatalogPropertiesMeta.METASTORE_URIS;
import static com.datastrato.gravitino.rel.transforms.Transforms.day;
import static com.datastrato.gravitino.rel.transforms.Transforms.identity;

import com.datastrato.gravitino.NameIdentifier;
import com.datastrato.gravitino.Namespace;
import com.datastrato.gravitino.catalog.hive.miniHMS.MiniHiveMetastoreService;
import com.datastrato.gravitino.exceptions.NoSuchSchemaException;
import com.datastrato.gravitino.exceptions.TableAlreadyExistsException;
import com.datastrato.gravitino.meta.AuditInfo;
import com.datastrato.gravitino.meta.CatalogEntity;
import com.datastrato.gravitino.rel.Column;
import com.datastrato.gravitino.rel.Distribution;
import com.datastrato.gravitino.rel.Distribution.Strategy;
import com.datastrato.gravitino.rel.SortOrder;
import com.datastrato.gravitino.rel.SortOrder.Direction;
import com.datastrato.gravitino.rel.SortOrder.NullOrdering;
import com.datastrato.gravitino.rel.Table;
import com.datastrato.gravitino.rel.TableChange;
import com.datastrato.gravitino.rel.transforms.Transform;
import com.datastrato.gravitino.rel.transforms.Transforms;
import com.google.common.collect.Maps;
import io.substrait.type.TypeCreator;
import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import org.apache.hadoop.hive.conf.HiveConf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class TestHiveTable extends MiniHiveMetastoreService {

  private static final String META_LAKE_NAME = "metalake";

  private static final String HIVE_CATALOG_NAME = "test_catalog";
  private static final String HIVE_SCHEMA_NAME = "test_schema";
  private static final String HIVE_COMMENT = "test_comment";
  private static HiveCatalog hiveCatalog;
  private static HiveSchema hiveSchema;
  private static final NameIdentifier schemaIdent =
      NameIdentifier.of(META_LAKE_NAME, HIVE_CATALOG_NAME, HIVE_SCHEMA_NAME);

  @BeforeAll
  private static void setup() {
    initHiveCatalog();
    initHiveSchema();
  }

  @AfterEach
  private void resetSchema() {
    hiveCatalog.asSchemas().dropSchema(schemaIdent, true);
    initHiveSchema();
  }

  private static void initHiveSchema() {
    Map<String, String> properties = Maps.newHashMap();
    properties.put("key1", "val1");
    properties.put("key2", "val2");

    hiveSchema =
        (HiveSchema) hiveCatalog.asSchemas().createSchema(schemaIdent, HIVE_COMMENT, properties);
  }

  private static void initHiveCatalog() {
    AuditInfo auditInfo =
        new AuditInfo.Builder().withCreator("testHiveUser").withCreateTime(Instant.now()).build();

    CatalogEntity entity =
        new CatalogEntity.Builder()
            .withId(1L)
            .withName(HIVE_CATALOG_NAME)
            .withNamespace(Namespace.of(META_LAKE_NAME))
            .withType(HiveCatalog.Type.RELATIONAL)
            .withProvider("hive")
            .withAuditInfo(auditInfo)
            .build();

    Map<String, String> conf = Maps.newHashMap();
    metastore.hiveConf().forEach(e -> conf.put(e.getKey(), e.getValue()));

    conf.put(METASTORE_URIS, hiveConf.get(HiveConf.ConfVars.METASTOREURIS.varname));
    conf.put(
        CATALOG_BYPASS_PREFIX + HiveConf.ConfVars.METASTOREWAREHOUSE.varname,
        hiveConf.get(HiveConf.ConfVars.METASTOREWAREHOUSE.varname));
    conf.put(
        CATALOG_BYPASS_PREFIX
            + HiveConf.ConfVars.METASTORE_DISALLOW_INCOMPATIBLE_COL_TYPE_CHANGES.varname,
        hiveConf.get(HiveConf.ConfVars.METASTORE_DISALLOW_INCOMPATIBLE_COL_TYPE_CHANGES.varname));

    conf.put(
        CATALOG_BYPASS_PREFIX + HiveConf.ConfVars.HIVE_IN_TEST.varname,
        hiveConf.get(HiveConf.ConfVars.HIVE_IN_TEST.varname));

    hiveCatalog = new HiveCatalog().withCatalogConf(conf).withCatalogEntity(entity);
  }

  private Distribution createDistribution() {
    return Distribution.builder()
        .withNumber(10)
        .withTransforms(new Transform[] {Transforms.field(new String[] {"col_1"})})
        .withStrategy(Strategy.EVEN)
        .build();
  }

  private SortOrder[] createSortOrder() {
    return new SortOrder[] {
      SortOrder.builder()
          .withNullOrdering(NullOrdering.FIRST)
          .withDirection(Direction.DESC)
          .withTransform(Transforms.field(new String[] {"col_2"}))
          .build()
    };
  }

  @Test
  public void testCreateHiveTable() {
    String hiveTableName = "test_hive_table";
    NameIdentifier tableIdentifier =
        NameIdentifier.of(META_LAKE_NAME, hiveCatalog.name(), hiveSchema.name(), hiveTableName);
    Map<String, String> properties = Maps.newHashMap();
    properties.put("key1", "val1");
    properties.put("key2", "val2");

    HiveColumn col1 =
        new HiveColumn.Builder()
            .withName("col_1")
            .withType(TypeCreator.NULLABLE.I8)
            .withComment(HIVE_COMMENT)
            .build();
    HiveColumn col2 =
        new HiveColumn.Builder()
            .withName("col_2")
            .withType(TypeCreator.NULLABLE.DATE)
            .withComment(HIVE_COMMENT)
            .build();
    Column[] columns = new Column[] {col1, col2};

    Distribution distribution = createDistribution();
    SortOrder[] sortOrders = createSortOrder();

    Table table =
        hiveCatalog
            .asTableCatalog()
            .createTable(
                tableIdentifier,
                columns,
                HIVE_COMMENT,
                properties,
                new Transform[0],
                distribution,
                sortOrders);
    Assertions.assertEquals(tableIdentifier.name(), table.name());
    Assertions.assertEquals(HIVE_COMMENT, table.comment());
    Assertions.assertEquals("val1", table.properties().get("key1"));
    Assertions.assertEquals("val2", table.properties().get("key2"));

    Table loadedTable = hiveCatalog.asTableCatalog().loadTable(tableIdentifier);
    Assertions.assertEquals(table.auditInfo().creator(), loadedTable.auditInfo().creator());
    Assertions.assertNull(loadedTable.auditInfo().lastModifier());
    Assertions.assertNull(loadedTable.auditInfo().lastModifiedTime());

    Assertions.assertEquals("val1", loadedTable.properties().get("key1"));
    Assertions.assertEquals("val2", loadedTable.properties().get("key2"));

    Assertions.assertTrue(hiveCatalog.asTableCatalog().tableExists(tableIdentifier));
    NameIdentifier[] tableIdents =
        hiveCatalog.asTableCatalog().listTables(tableIdentifier.namespace());
    Assertions.assertTrue(Arrays.asList(tableIdents).contains(tableIdentifier));

    // Compare sort and order
    Assertions.assertEquals(distribution.number(), loadedTable.distribution().number());
    Assertions.assertArrayEquals(
        distribution.transforms(), loadedTable.distribution().transforms());

    Assertions.assertEquals(sortOrders.length, loadedTable.sortOrder().length);
    for (int i = 0; i < loadedTable.sortOrder().length; i++) {
      Assertions.assertEquals(
          sortOrders[i].getDirection(), loadedTable.sortOrder()[i].getDirection());
      Assertions.assertEquals(
          sortOrders[i].getTransform(), loadedTable.sortOrder()[i].getTransform());
    }

    // Test exception
    Throwable exception =
        Assertions.assertThrows(
            TableAlreadyExistsException.class,
            () ->
                hiveCatalog
                    .asTableCatalog()
                    .createTable(
                        tableIdentifier,
                        columns,
                        HIVE_COMMENT,
                        properties,
                        new Transform[0],
                        distribution,
                        sortOrders));
    Assertions.assertTrue(exception.getMessage().contains("Table already exists"));

    HiveColumn illegalColumn =
        new HiveColumn.Builder()
            .withName("col_3")
            .withType(TypeCreator.REQUIRED.I8)
            .withComment(HIVE_COMMENT)
            .build();

    exception =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () ->
                hiveCatalog
                    .asTableCatalog()
                    .createTable(
                        tableIdentifier,
                        new Column[] {illegalColumn},
                        HIVE_COMMENT,
                        properties,
                        new Transform[0],
                        distribution,
                        sortOrders));
    Assertions.assertTrue(
        exception
            .getMessage()
            .contains(
                "The NOT NULL constraint for column is only supported since Hive 3.0, "
                    + "but the current Gravitino Hive catalog only supports Hive 2.x"));
  }

  @Test
  public void testCreatePartitionedHiveTable() {
    NameIdentifier tableIdentifier =
        NameIdentifier.of(META_LAKE_NAME, hiveCatalog.name(), hiveSchema.name(), genRandomName());
    Map<String, String> properties = Maps.newHashMap();
    properties.put("key1", "val1");
    properties.put("key2", "val2");

    HiveColumn col1 =
        new HiveColumn.Builder()
            .withName("city")
            .withType(TypeCreator.NULLABLE.I8)
            .withComment(HIVE_COMMENT)
            .build();
    HiveColumn col2 =
        new HiveColumn.Builder()
            .withName("dt")
            .withType(TypeCreator.NULLABLE.DATE)
            .withComment(HIVE_COMMENT)
            .build();
    Column[] columns = new Column[] {col1, col2};

    Transform[] partitions = new Transform[] {identity(new String[] {col1.name()})};

    Table table =
        hiveCatalog
            .asTableCatalog()
            .createTable(tableIdentifier, columns, HIVE_COMMENT, properties, partitions);
    Assertions.assertEquals(tableIdentifier.name(), table.name());
    Assertions.assertEquals(HIVE_COMMENT, table.comment());
    Assertions.assertEquals("val1", table.properties().get("key1"));
    Assertions.assertEquals("val2", table.properties().get("key2"));
    Assertions.assertArrayEquals(partitions, table.partitioning());

    Table loadedTable = hiveCatalog.asTableCatalog().loadTable(tableIdentifier);

    Assertions.assertEquals(table.auditInfo().creator(), loadedTable.auditInfo().creator());
    Assertions.assertNull(loadedTable.auditInfo().lastModifier());
    Assertions.assertNull(loadedTable.auditInfo().lastModifiedTime());

    Assertions.assertEquals("val1", loadedTable.properties().get("key1"));
    Assertions.assertEquals("val2", loadedTable.properties().get("key2"));
    Assertions.assertArrayEquals(partitions, loadedTable.partitioning());

    Assertions.assertTrue(hiveCatalog.asTableCatalog().tableExists(tableIdentifier));
    NameIdentifier[] tableIdents =
        hiveCatalog.asTableCatalog().listTables(tableIdentifier.namespace());
    Assertions.assertTrue(Arrays.asList(tableIdents).contains(tableIdentifier));

    // Test exception
    Throwable exception =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () ->
                hiveCatalog
                    .asTableCatalog()
                    .createTable(
                        tableIdentifier,
                        columns,
                        HIVE_COMMENT,
                        properties,
                        new Transform[] {day(new String[] {col2.name()})}));
    Assertions.assertTrue(
        exception.getMessage().contains("Hive partition only supports identity transform"));

    exception =
        Assertions.assertThrows(
            RuntimeException.class,
            () ->
                hiveCatalog
                    .asTableCatalog()
                    .createTable(
                        NameIdentifier.of(
                            META_LAKE_NAME, hiveCatalog.name(), hiveSchema.name(), genRandomName()),
                        columns,
                        HIVE_COMMENT,
                        properties,
                        new Transform[] {identity(new String[] {col1.name(), col2.name()})}));
    Assertions.assertTrue(
        exception.getMessage().contains("Hive partition does not support nested field"));

    exception =
        Assertions.assertThrows(
            RuntimeException.class,
            () ->
                hiveCatalog
                    .asTableCatalog()
                    .createTable(
                        NameIdentifier.of(
                            META_LAKE_NAME, hiveCatalog.name(), hiveSchema.name(), genRandomName()),
                        columns,
                        HIVE_COMMENT,
                        properties,
                        new Transform[] {identity(new String[] {"not_exist_field"})}));
    Assertions.assertTrue(exception.getMessage().contains("Hive partition must match one column"));
  }

  @Test
  public void testDropHiveTable() {
    NameIdentifier tableIdentifier =
        NameIdentifier.of(META_LAKE_NAME, hiveCatalog.name(), hiveSchema.name(), genRandomName());
    Map<String, String> properties = Maps.newHashMap();
    properties.put("key1", "val1");
    properties.put("key2", "val2");

    HiveColumn col1 =
        new HiveColumn.Builder()
            .withName("col_1")
            .withType(TypeCreator.NULLABLE.I8)
            .withComment(HIVE_COMMENT)
            .build();
    HiveColumn col2 =
        new HiveColumn.Builder()
            .withName("col_2")
            .withType(TypeCreator.NULLABLE.DATE)
            .withComment(HIVE_COMMENT)
            .build();
    Column[] columns = new Column[] {col1, col2};

    hiveCatalog
        .asTableCatalog()
        .createTable(
            tableIdentifier,
            columns,
            HIVE_COMMENT,
            properties,
            new Transform[0],
            Distribution.NONE,
            new SortOrder[0]);

    Assertions.assertTrue(hiveCatalog.asTableCatalog().tableExists(tableIdentifier));
    hiveCatalog.asTableCatalog().dropTable(tableIdentifier);
    Assertions.assertFalse(hiveCatalog.asTableCatalog().tableExists(tableIdentifier));
  }

  @Test
  public void testListTableException() {
    Namespace tableNs = Namespace.of("metalake", hiveCatalog.name(), "not_exist_db");
    Throwable exception =
        Assertions.assertThrows(
            NoSuchSchemaException.class, () -> hiveCatalog.asTableCatalog().listTables(tableNs));
    Assertions.assertTrue(exception.getMessage().contains("Schema (database) does not exist"));
  }

  @Test
  public void testAlterHiveTable() throws IOException {
    // create a table with random name
    NameIdentifier tableIdentifier =
        NameIdentifier.of(META_LAKE_NAME, hiveCatalog.name(), hiveSchema.name(), genRandomName());
    Map<String, String> properties = Maps.newHashMap();
    properties.put("key1", "val1");
    properties.put("key2", "val2");

    HiveColumn col1 =
        new HiveColumn.Builder()
            .withName("col_1")
            .withType(TypeCreator.NULLABLE.I8)
            .withComment(HIVE_COMMENT)
            .build();
    HiveColumn col2 =
        new HiveColumn.Builder()
            .withName("col_2")
            .withType(TypeCreator.NULLABLE.DATE)
            .withComment(HIVE_COMMENT)
            .build();
    Column[] columns = new Column[] {col1, col2};

    Distribution distribution = createDistribution();
    SortOrder[] sortOrders = createSortOrder();

    Table createdTable =
        hiveCatalog
            .asTableCatalog()
            .createTable(
                tableIdentifier,
                columns,
                HIVE_COMMENT,
                properties,
                new Transform[0],
                distribution,
                sortOrders);
    Assertions.assertTrue(hiveCatalog.asTableCatalog().tableExists(tableIdentifier));

    // test exception
    Throwable exception =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () ->
                hiveCatalog
                    .asTableCatalog()
                    .alterTable(
                        tableIdentifier,
                        TableChange.updateColumnPosition(
                            new String[] {"not_exist_col"},
                            TableChange.ColumnPosition.after("col_1"))));
    Assertions.assertTrue(exception.getMessage().contains("UpdateColumnPosition does not exist"));

    exception =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () ->
                hiveCatalog
                    .asTableCatalog()
                    .alterTable(
                        tableIdentifier,
                        TableChange.updateColumnPosition(
                            new String[] {"col_1"},
                            TableChange.ColumnPosition.after("not_exist_col"))));
    Assertions.assertTrue(exception.getMessage().contains("Column does not exist"));

    exception =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () ->
                hiveCatalog
                    .asTableCatalog()
                    .alterTable(
                        tableIdentifier,
                        TableChange.updateColumnPosition(new String[] {"col_1"}, null)));
    Assertions.assertTrue(exception.getMessage().contains("Column position cannot be null"));

    exception =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () ->
                hiveCatalog
                    .asTableCatalog()
                    .alterTable(
                        tableIdentifier,
                        TableChange.addColumn(new String[] {"col_1"}, TypeCreator.REQUIRED.I8)));
    Assertions.assertTrue(
        exception
            .getMessage()
            .contains(
                "The NOT NULL constraint for column is only supported since Hive 3.0, "
                    + "but the current Gravitino Hive catalog only supports Hive 2.x"));

    exception =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () ->
                hiveCatalog
                    .asTableCatalog()
                    .alterTable(
                        tableIdentifier,
                        TableChange.updateColumnType(
                            new String[] {"col_1"}, TypeCreator.REQUIRED.I8)));
    Assertions.assertTrue(
        exception
            .getMessage()
            .contains(
                "The NOT NULL constraint for column is only supported since Hive 3.0, "
                    + "but the current Gravitino Hive catalog only supports Hive 2.x"));

    // test alter
    hiveCatalog
        .asTableCatalog()
        .alterTable(
            tableIdentifier,
            TableChange.rename("test_hive_table_new"),
            TableChange.updateComment(HIVE_COMMENT + "_new"),
            TableChange.removeProperty("key1"),
            TableChange.setProperty("key2", "val2_new"),
            // columns current format: [col_1:I8:comment, col_2:DATE:comment]
            TableChange.addColumn(new String[] {"col_3"}, TypeCreator.NULLABLE.STRING),
            // columns current format: [col_1:I8:comment, col_2:DATE:comment, col_3:STRING:null]
            TableChange.renameColumn(new String[] {"col_2"}, "col_2_new"),
            // columns current format: [col_1:I8:comment, col_2_new:DATE:comment, col_3:STRING:null]
            TableChange.updateColumnComment(new String[] {"col_1"}, HIVE_COMMENT + "_new"),
            // columns current format: [col_1:I8:comment_new, col_2_new:DATE:comment,
            // col_3:STRING:null]
            TableChange.updateColumnType(new String[] {"col_1"}, TypeCreator.NULLABLE.I32),
            // columns current format: [col_1:I32:comment_new, col_2_new:DATE:comment,
            // col_3:STRING:null]
            TableChange.updateColumnPosition(
                new String[] {"col_2_new"}, TableChange.ColumnPosition.first())
            // columns current: [col_2_new:DATE:comment, col_1:I32:comment_new, col_3:STRING:null]
            );
    Table alteredTable =
        hiveCatalog
            .asTableCatalog()
            .loadTable(NameIdentifier.of(tableIdentifier.namespace(), "test_hive_table_new"));

    Assertions.assertEquals(HIVE_COMMENT + "_new", alteredTable.comment());
    Assertions.assertFalse(alteredTable.properties().containsKey("key1"));
    Assertions.assertEquals(alteredTable.properties().get("key2"), "val2_new");

    Assertions.assertEquals(createdTable.auditInfo().creator(), alteredTable.auditInfo().creator());
    Assertions.assertNull(alteredTable.auditInfo().lastModifier());
    Assertions.assertNull(alteredTable.auditInfo().lastModifiedTime());
    Assertions.assertNotNull(alteredTable.partitioning());
    Assertions.assertArrayEquals(createdTable.partitioning(), alteredTable.partitioning());

    Column[] expected =
        new Column[] {
          new HiveColumn.Builder()
              .withName("col_2_new")
              .withType(TypeCreator.NULLABLE.DATE)
              .withComment(HIVE_COMMENT)
              .build(),
          new HiveColumn.Builder()
              .withName("col_1")
              .withType(TypeCreator.NULLABLE.I32)
              .withComment(HIVE_COMMENT + "_new")
              .build(),
          new HiveColumn.Builder()
              .withName("col_3")
              .withType(TypeCreator.NULLABLE.STRING)
              .withComment(null)
              .build()
        };
    Assertions.assertArrayEquals(expected, alteredTable.columns());

    // test delete column change
    hiveCatalog
        .asTableCatalog()
        .alterTable(
            NameIdentifier.of(tableIdentifier.namespace(), "test_hive_table_new"),
            TableChange.deleteColumn(new String[] {"not_exist_col"}, true));

    hiveCatalog
        .asTableCatalog()
        .alterTable(
            NameIdentifier.of(tableIdentifier.namespace(), "test_hive_table_new"),
            TableChange.deleteColumn(new String[] {"col_1"}, false));
    Table alteredTable1 =
        hiveCatalog
            .asTableCatalog()
            .loadTable(NameIdentifier.of(tableIdentifier.namespace(), "test_hive_table_new"));
    expected =
        Arrays.stream(expected).filter(c -> !"col_1".equals(c.name())).toArray(Column[]::new);
    Assertions.assertArrayEquals(expected, alteredTable1.columns());

    Assertions.assertEquals(
        createdTable.auditInfo().creator(), alteredTable1.auditInfo().creator());
    Assertions.assertNull(alteredTable1.auditInfo().lastModifier());
    Assertions.assertNull(alteredTable1.auditInfo().lastModifiedTime());
    Assertions.assertNotNull(alteredTable.partitioning());
    Assertions.assertArrayEquals(createdTable.partitioning(), alteredTable.partitioning());
  }
}