package com.datastax.oss.pulsaroperator.controllers;

import com.datastax.oss.pulsaroperator.autoscaler.AutoscalerDaemon;
import com.datastax.oss.pulsaroperator.crds.CRDUtil;
import com.datastax.oss.pulsaroperator.crds.bookkeeper.BookKeeper;
import com.datastax.oss.pulsaroperator.crds.bookkeeper.BookKeeperFullSpec;
import com.datastax.oss.pulsaroperator.crds.broker.Broker;
import com.datastax.oss.pulsaroperator.crds.broker.BrokerFullSpec;
import com.datastax.oss.pulsaroperator.crds.cluster.PulsarCluster;
import com.datastax.oss.pulsaroperator.crds.cluster.PulsarClusterSpec;
import com.datastax.oss.pulsaroperator.crds.proxy.Proxy;
import com.datastax.oss.pulsaroperator.crds.proxy.ProxyFullSpec;
import com.datastax.oss.pulsaroperator.crds.zookeeper.ZooKeeper;
import com.datastax.oss.pulsaroperator.crds.zookeeper.ZooKeeperFullSpec;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.javaoperatorsdk.operator.api.reconciler.Constants;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.quarkus.runtime.ShutdownEvent;
import java.util.List;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import lombok.SneakyThrows;
import lombok.extern.jbosslog.JBossLog;

@ControllerConfiguration(namespaces = Constants.WATCH_CURRENT_NAMESPACE, name = "pulsar-cluster-app")
@JBossLog
@ApplicationScoped
public class PulsarClusterController extends AbstractController<PulsarCluster> {

    public static final String CUSTOM_RESOURCE_BROKER = "broker";
    public static final String CUSTOM_RESOURCE_BOOKKEEPER = "bookkeeper";
    public static final String CUSTOM_RESOURCE_ZOOKEEPER = "zookeeper";
    public static final String CUSTOM_RESOURCE_PROXY = "proxy";

    private final AutoscalerDaemon autoscaler;

    public PulsarClusterController(KubernetesClient client) {
        super(client);
        autoscaler = new AutoscalerDaemon(client);
    }

    @Override
    protected void patchResources(PulsarCluster resource, Context<PulsarCluster> context)
            throws Exception {

        final String currentNamespace = resource.getMetadata().getNamespace();
        final PulsarClusterSpec clusterSpec = resource.getSpec();

        final List<OwnerReference> ownerReference = List.of(getOwnerReference(resource));

        patchCustomResource(
                CUSTOM_RESOURCE_ZOOKEEPER,
                ZooKeeper.class,
                ZooKeeperFullSpec.builder()
                        .global(clusterSpec.getGlobal())
                        .zookeeper(clusterSpec.getZookeeper())
                        .build(),
                currentNamespace,
                clusterSpec,
                ownerReference
        );

        patchCustomResource(CUSTOM_RESOURCE_BOOKKEEPER,
                BookKeeper.class,
                BookKeeperFullSpec.builder()
                        .global(clusterSpec.getGlobal())
                        .bookkeeper(clusterSpec.getBookkeeper())
                        .build(),
                currentNamespace,
                clusterSpec,
                ownerReference
        );

        PulsarClusterSpec pulsarClusterSpecWithDefaults = CRDUtil.deepCloneObject(clusterSpec);
        pulsarClusterSpecWithDefaults.applyDefaults(clusterSpec.getGlobalSpec());

        if (pulsarClusterSpecWithDefaults.getBroker() != null
                && pulsarClusterSpecWithDefaults.getBroker().getAutoscaler() != null
                && pulsarClusterSpecWithDefaults.getBroker().getAutoscaler().getEnabled()) {

            final String crFullName = "%s-%s".formatted(clusterSpec.getGlobal().getName(), CUSTOM_RESOURCE_BROKER);
            final Broker current = client.resources(Broker.class)
                    .inNamespace(currentNamespace)
                    .withName(crFullName)
                    .get();
            if (current != null) {
                final Integer currentReplicas = current.getSpec().getBroker().getReplicas();
                // do not update replicas if patching, leave whatever the autoscaler have set
                clusterSpec.getBroker().setReplicas(currentReplicas);
                pulsarClusterSpecWithDefaults.getBroker().setReplicas(currentReplicas);
            }
        }
        patchCustomResource(
                CUSTOM_RESOURCE_BROKER,
                Broker.class,
                BrokerFullSpec.builder()
                        .global(clusterSpec.getGlobal())
                        .broker(clusterSpec.getBroker())
                        .build(),
                currentNamespace,
                clusterSpec,
                ownerReference
        );
        autoscaler.onSpecChange(pulsarClusterSpecWithDefaults, currentNamespace);

        patchCustomResource(CUSTOM_RESOURCE_PROXY,
                Proxy.class,
                ProxyFullSpec.builder()
                        .global(clusterSpec.getGlobal())
                        .proxy(clusterSpec.getProxy())
                        .build(),
                currentNamespace,
                clusterSpec,
                ownerReference
        );
    }

    @SneakyThrows
    private <CR extends CustomResource<SPEC, ?>, SPEC> void patchCustomResource(
            String customResourceName,
            Class<CR> resourceClass,
            SPEC spec,
            String namespace,
            PulsarClusterSpec clusterSpec,
            List<OwnerReference> ownerReferences) {
        final MixedOperation<CR, KubernetesResourceList<CR>, Resource<CR>> resourceClient =
                client.resources(resourceClass);
        if (resourceClient == null) {
            throw new IllegalStateException(customResourceName + " CRD not found");
        }

        ObjectMeta meta = new ObjectMeta();
        final String crFullName = "%s-%s".formatted(clusterSpec.getGlobal().getName(), customResourceName);
        meta.setName(crFullName);
        meta.setNamespace(namespace);
        meta.setOwnerReferences(ownerReferences);

        final CR resource = resourceClass.getConstructor().newInstance();
        resource.setMetadata(meta);
        resource.setSpec(spec);

        final CR current = client.resources(resourceClass)
                .inNamespace(namespace)
                .withName(crFullName)
                .get();
        if (current == null) {
            client.resource(resource)
                    .inNamespace(namespace)
                    .create();
        } else {
            client
                    .resource(current)
                    .inNamespace(namespace)
                    .patch(resource);
        }

        resourceClient
                .inNamespace(namespace)
                .resource(resource)
                .createOrReplace();
        log.infof("Patched custom resource %s with name %s ", customResourceName, crFullName);
    }

    void onStop(@Observes ShutdownEvent ev) {
        if (autoscaler != null) {
            autoscaler.close();
        }
    }
}

