package com.winllc.dsp.store;

import com.winllc.dsp.analysis.AnalysisDefinition;
import com.winllc.dsp.config.PortalProperties;
import com.winllc.dsp.metrics.Dashboard;
import com.winllc.dsp.model.DirectoryDefinition;
import com.winllc.dsp.model.FormDefinition;
import com.winllc.dsp.model.PermissionGrant;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Seeds example forms, directories and grants (matching the demo directory) into an empty store. */
@Component
public class DemoConfigSeeder implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(DemoConfigSeeder.class);

  public record DemoConfig(List<FormDefinition> forms, List<DirectoryDefinition> definitions, List<PermissionGrant> grants,
      List<Dashboard> dashboards, List<AnalysisDefinition> analyses) {}

  private final ConfigStore store;
  private final PortalProperties props;
  private final JsonMapper mapper;

  public DemoConfigSeeder(ConfigStore store, PortalProperties props, JsonMapper mapper) {
    this.store = store;
    this.props = props;
    this.mapper = mapper;
  }

  @Override
  public void run(ApplicationArguments args) throws IOException {
    if (!store.snapshot().isEmpty() || !props.shouldSeedDemoConfig()) return;
    DemoConfig demo = load(mapper);
    store.update(d -> {
      apply(demo, d);
      return null;
    });
    log.info("Seeded demo forms, directories, permissions, dashboards and analyses");
  }

  public static DemoConfig load(JsonMapper mapper) throws IOException {
    try (InputStream in = new ClassPathResource("demo/portal-config.json").getInputStream()) {
      return mapper.readValue(in, DemoConfig.class);
    }
  }

  public static void apply(DemoConfig demo, StoreData d) {
    String now = Instant.now().toString();
    d.forms = new java.util.ArrayList<>(demo.forms().stream()
        .map(f -> new FormDefinition(f.id(), f.name(), f.description(), f.objectClasses(), f.rdnAttribute(), f.fields(), now, now))
        .toList());
    d.definitions = new java.util.ArrayList<>(demo.definitions().stream()
        .map(x -> new DirectoryDefinition(x.id(), x.name(), x.slug(), x.description(), x.formId(), x.baseDn(), x.scope(), x.filter(),
            x.mode(), x.everyoneCanRead(), x.listAttributes(), x.searchAttributes(), x.titleAttribute(), x.createContainers(),
            x.selfMatch(), x.icon(), now, now))
        .toList());
    d.grants = new java.util.ArrayList<>(demo.grants().stream()
        .map(g -> new PermissionGrant(g.id(), g.definitionId(), g.subjectType(), g.subject(), g.subjectLabel(), g.access(), now))
        .toList());
    d.dashboards = new java.util.ArrayList<>((demo.dashboards() == null ? List.<Dashboard>of() : demo.dashboards()).stream()
        .map(x -> new Dashboard(x.id(), x.name(), x.description(), x.shared(), x.widgets(), now, now))
        .toList());
    d.analyses = new java.util.ArrayList<>((demo.analyses() == null ? List.<AnalysisDefinition>of() : demo.analyses()).stream()
        .map(x -> new AnalysisDefinition(x.id(), x.name(), x.description(), x.type(), x.sources(), x.compare(), x.quality(), now, now, null))
        .toList());
  }
}
