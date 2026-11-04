package com.winllc.dsp.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.winllc.dsp.cluster.Cluster;
import com.winllc.dsp.cluster.DatabaseProperties;
import com.winllc.dsp.config.PortalProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Configuration written before there were several directories (version 1, DATA_DIR's
 * portal-config.json): at the first start its definitions and grants become the default
 * directory's, and the file is written back as version 2. The same upgrade applies to the shared
 * database's copy (SharedStoreTest, which needs Docker).
 */
class ConfigMigrationTest {
  static final String V1 = """
      {
        "version": 1,
        "forms": [{"id": "form-person", "name": "Person", "objectClasses": ["inetOrgPerson"], "rdnAttribute": "uid", "fields": []}],
        "definitions": [
          {"id": "def-staff", "name": "Staff", "slug": "staff", "formId": "form-person", "baseDn": "ou=people,dc=example,dc=com",
           "scope": "one", "mode": "readwrite", "everyoneCanRead": false, "listAttributes": ["cn"], "searchAttributes": ["cn"],
           "titleAttribute": "cn", "createContainers": [], "selfMatch": {"type": "dn"}}
        ],
        "grants": [
          {"id": "g-1", "definitionId": "def-staff", "subjectType": "user", "subject": "alice", "access": "read"},
          {"id": "g-2", "definitionId": "def-gone", "subjectType": "group", "subject": "cn=hr,dc=example,dc=com", "access": "write"}
        ]
      }
      """;

  private static ConfigStore open(Path dir, JsonMapper mapper) {
    PortalProperties props = new PortalProperties();
    props.setDataDir(dir.toString());
    return new ConfigStore(mapper, props, Cluster.standalone(), new DatabaseProperties());
  }

  @Test
  void definitionsAndGrantsOfAnEarlierVersionBelongToTheDefaultDirectory() throws Exception {
    JsonMapper mapper = JsonMapper.builder().build();
    Path dir = Files.createTempDirectory("dsp-migrate");
    Path file = dir.resolve("portal-config.json");
    Files.writeString(file, V1);

    ConfigStore store = open(dir, mapper);
    StoreData d = store.snapshot();
    assertThat(d.version).isEqualTo(StoreData.CURRENT_VERSION);
    assertThat(d.definitions).allSatisfy(x -> assertThat(x.directory()).isEqualTo("default"));
    assertThat(d.grants).allSatisfy(x -> assertThat(x.directory()).isEqualTo("default"));

    // Written back once, so other tools reading the file (and the next start) see version 2.
    JsonNode written = mapper.readTree(Files.readString(file));
    assertThat(written.get("version").asInt()).isEqualTo(2);
    assertThat(written.get("definitions").get(0).get("directory").asString()).isEqualTo("default");
    assertThat(written.get("grants").get(1).get("directory").asString()).isEqualTo("default");

    // A second start changes nothing.
    long modified = Files.getLastModifiedTime(file).toMillis();
    Thread.sleep(5);
    assertThat(open(dir, mapper).snapshot().definitions.get(0).directory()).isEqualTo("default");
    assertThat(Files.getLastModifiedTime(file).toMillis()).isEqualTo(modified);
  }

  @Test
  void grantsTakeTheirDefinitionsDirectory() {
    StoreData d = new StoreData();
    d.version = 1;
    d.definitions.add(new com.winllc.dsp.model.DirectoryDefinition("def-lab", "Lab", "lab", null, "f", "dc=lab", "one", null, "readwrite",
        false, java.util.List.of("cn"), java.util.List.of(), "cn", java.util.List.of(), null, null, null, null, "lab"));
    d.grants.add(new com.winllc.dsp.model.PermissionGrant("g", "def-lab", "user", "zoe", null, "read", null));
    assertThat(ConfigStore.migrateDirectories(d)).isTrue();
    assertThat(d.grants.get(0).directory()).isEqualTo("lab");
    assertThat(d.definitions.get(0).directory()).isEqualTo("lab");
    assertThat(ConfigStore.migrateDirectories(d)).isFalse();
  }
}
