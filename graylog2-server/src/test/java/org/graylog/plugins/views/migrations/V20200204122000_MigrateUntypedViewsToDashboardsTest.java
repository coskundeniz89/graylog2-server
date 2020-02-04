package org.graylog.plugins.views.migrations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.graylog.testing.mongodb.MongoDBFixtures;
import org.graylog.testing.mongodb.MongoDBInstance;
import org.graylog2.database.MongoConnection;
import org.graylog2.migrations.Migration;
import org.graylog2.plugin.cluster.ClusterConfigService;
import org.graylog2.shared.bindings.providers.ObjectMapperProvider;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.skyscreamer.jsonassert.JSONAssert;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.graylog.plugins.views.migrations.V20200204122000_MigrateUntypedViewsToDashboards.MigrationCompleted;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.internal.verification.VerificationModeFactory.times;

public class V20200204122000_MigrateUntypedViewsToDashboardsTest {
    @Rule
    public final MongoDBInstance mongodb = MongoDBInstance.createForClass();
    @Rule
    public final MockitoRule mockitoRule = MockitoJUnit.rule();

    @Mock
    private ClusterConfigService clusterConfigService;

    private final ObjectMapper objectMapper = new ObjectMapperProvider().get();

    private Migration migration;
    private MongoCollection<Document> viewsCollection;

    @Before
    public void setUp() throws Exception {
        this.viewsCollection = spy(mongodb.mongoConnection().getMongoDatabase().getCollection("views"));
        final MongoConnection mongoConnection = mock(MongoConnection.class, Answers.RETURNS_DEEP_STUBS);
        when(mongoConnection.getMongoDatabase().getCollection("views")).thenReturn(viewsCollection);
        this.migration = new V20200204122000_MigrateUntypedViewsToDashboards(mongoConnection, clusterConfigService);
    }

    @Test
    public void runsIfNoViewsArePresent() {
        this.migration.upgrade();
    }

    @Test
    public void writesMigrationCompletedAfterSuccess() {
        this.migration.upgrade();

        final MigrationCompleted migrationCompleted = captureMigrationCompleted();
        assertThat(migrationCompleted.viewIds()).isEmpty();
    }

    @Test
    public void doesNotRunAgainIfMigrationHadCompletedBefore() {
        when(clusterConfigService.get(MigrationCompleted.class)).thenReturn(MigrationCompleted.create(Collections.emptyList()));

        this.migration.upgrade();

        verify(clusterConfigService, never()).write(any());
    }

    @Test
    @MongoDBFixtures("V20200204122000_MigrateUntypedViewsToDashboardsTest/untyped_view_with_widgets_with_filter.json")
    public void migratesWidgetFiltersToWidgetQueries() throws Exception {
        this.migration.upgrade();

        final MigrationCompleted migrationCompleted = captureMigrationCompleted();
        assertThat(migrationCompleted.viewIds()).containsExactly("5c8a613a844d02001a1fd2f4");

        assertSavedViews(1, resourceFile("V20200204122000_MigrateUntypedViewsToDashboardsTest/untyped_view_with_widgets_with_filter-after.json"));
    }

    @Test
    @MongoDBFixtures("V20200204122000_MigrateUntypedViewsToDashboardsTest/untyped_view_with_no_widgets.json")
    public void migratesUntypedViewWithNoWidgets() throws Exception {
        this.migration.upgrade();

        final MigrationCompleted migrationCompleted = captureMigrationCompleted();
        assertThat(migrationCompleted.viewIds()).containsExactly("5c8a613a844d02001a1fd2f4");

        assertSavedViews(1, resourceFile("V20200204122000_MigrateUntypedViewsToDashboardsTest/untyped_view_with_no_widgets-after.json"));
    }

    @Test
    @MongoDBFixtures("V20200204122000_MigrateUntypedViewsToDashboardsTest/typed_views.json")
    public void doesNotChangeTypedViews() throws Exception {
        this.migration.upgrade();

        final MigrationCompleted migrationCompleted = captureMigrationCompleted();
        assertThat(migrationCompleted.viewIds()).isEmpty();

        verify(this.viewsCollection, never()).updateOne(any(), any());
    }

    @Test
    @MongoDBFixtures("V20200204122000_MigrateUntypedViewsToDashboardsTest/mixed_typed_and_untyped_views.json")
    public void migratesOnlyUntypedViewsIfMixedOnesArePresent() throws Exception {
        this.migration.upgrade();

        final MigrationCompleted migrationCompleted = captureMigrationCompleted();
        assertThat(migrationCompleted.viewIds()).containsExactly("5c8a613a844d02001a1fd2f4");

        assertSavedViews(1, resourceFile("V20200204122000_MigrateUntypedViewsToDashboardsTest/mixed_typed_and_untyped_views-after.json"));
    }

    private void assertSavedViews(int count, String viewsCollection) throws Exception {
        final ArgumentCaptor<Document> newViewsCaptor = ArgumentCaptor.forClass(Document.class);
        verify(this.viewsCollection, times(count)).updateOne(any(), newViewsCaptor.capture());
        final List<Document> newViews = newViewsCaptor.getAllValues();
        assertThat(newViews).hasSize(count);

        JSONAssert.assertEquals(viewsCollection, toJSON(newViews), true);
    }

    private MigrationCompleted captureMigrationCompleted() {
        final ArgumentCaptor<MigrationCompleted> migrationCompletedCaptor = ArgumentCaptor.forClass(MigrationCompleted.class);
        verify(clusterConfigService, times(1)).write(migrationCompletedCaptor.capture());
        return migrationCompletedCaptor.getValue();
    }

    private String toJSON(Object object) throws JsonProcessingException {
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(object);
    }

    private String resourceFile(String filename) {
        try {
            final URL resource = this.getClass().getResource(filename);
            final Path path = Paths.get(resource.toURI());
            final byte[] bytes = Files.readAllBytes(path);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException | URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }
}
