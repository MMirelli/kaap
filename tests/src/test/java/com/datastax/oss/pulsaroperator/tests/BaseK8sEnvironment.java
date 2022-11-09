package com.datastax.oss.pulsaroperator.tests;

import com.dajudge.kindcontainer.K3sContainer;
import com.dajudge.kindcontainer.K3sContainerVersion;
import com.dajudge.kindcontainer.KubernetesImageSpec;
import com.dajudge.kindcontainer.exception.ExecutionException;
import com.dajudge.kindcontainer.helm.Helm3Container;
import com.dajudge.kindcontainer.kubectl.KubectlContainer;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.events.v1.Event;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.awaitility.Awaitility;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.OutputFrame;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.MountableFile;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;

@Slf4j
public abstract class BaseK8sEnvironment {

    private static final String OPERATOR_IMAGE = System.getProperty("pulsar.operator.tests.operator.image",
            "datastax/pulsar-operator:latest");

    protected static final String PULSAR_IMAGE = System.getProperty("pulsar.operator.tests.pulsar.image",
            "datastax/lunastreaming-core:2.10_2.3");

    private static final boolean RECYCLE_CONTAINER_SAME_TEST_CLASS = Boolean
            .parseBoolean(System.getProperty("pulsar.operator.tests.container.recycle.sameclass", "true"));

    private static final boolean DEBUG_LOG_CONTAINER = Boolean
            .getBoolean("pulsar.operator.tests.container.log.debug");

    private static final boolean DEBUG_CONTAINER_KEEP = Boolean
            .parseBoolean(System.getProperty("pulsar.operator.tests.container.keep", "false"));


    protected static final String NAMESPACE = "ns";
    private static final DockerClient hostDockerClient = DockerClientFactory.lazyClient();
    protected ReusableK3sContainer container;
    protected KubernetesClient client;
    private Watch eventsWatch;
    private String operatorPodName;

    public static class ReusableK3sContainer<T extends ReusableK3sContainer<T>> extends K3sContainer<T> {

        Boolean reused;
        private Consumer<Helm3Container> helm3ContainerConsumer;
        private Helm3Container helm3;

        public ReusableK3sContainer(KubernetesImageSpec<K3sContainerVersion> imageSpec) {
            super(imageSpec);
            withReuse(true);
        }

        @Override
        protected void containerIsStarting(InspectContainerResponse containerInfo, boolean reused) {
            this.reused = reused;
            super.containerIsStarting(containerInfo, reused);
        }

        @Override
        protected void configure() {
            super.configure();
            withCommand(new String[]{
                    "server",
                    //"--kubelet-arg", "eviction-minimum-reclaim=imagefs.available=2%,nodefs.available=2%",
                    //"--kubelet-arg", "eviction-hard=memory.available<500Mi,nodefs.available<10Gi",
                    "--disable=traefik",
                    "--tls-san=" + this.getHost(),
                    String.format("--service-node-port-range=%d-%d", 30000, 32767)});
        }


        public void beforeStartHelm3(Consumer<Helm3Container> consumer) {
            helm3ContainerConsumer = consumer;
        }

        @Override
        public synchronized Helm3Container<?> helm3() {
            if (this.helm3 == null) {
                this.helm3 = (Helm3Container) (new Helm3Container(this::getInternalKubeconfig))
                        .withNetworkMode("container:" + this.getContainerId());
                if (helm3ContainerConsumer != null) {
                    helm3ContainerConsumer.accept(helm3);
                }
                this.helm3.start();
            }

            return this.helm3;
        }

        @Override
        public void stop() {
            if (helm3 != null) {
                helm3.stop();
            }
            super.stop();
        }
    }

    @SneakyThrows
    private static List<String> getYamlManifests() {
        return Files.list(
                        Path.of(MountableFile
                                .forClasspathResource("/manifests")
                                .getResolvedPath())
                ).filter(p -> p.toFile().getName().endsWith(".yml"))
                .map(p -> "/manifests/" + p.getFileName().toFile().getName())
                .collect(Collectors.toList());
    }

    @BeforeMethod(alwaysRun = true)
    public void before() throws Exception {
        boolean containerWasNull = container == null;
        if (containerWasNull) {
            final KubernetesImageSpec<K3sContainerVersion> k3sImage =
                    new KubernetesImageSpec<>(K3sContainerVersion.VERSION_1_25_0)
                            .withImage("rancher/k3s:v1.25.3-k3s1");
            container = new ReusableK3sContainer(k3sImage);
            createAndMountImageDigest(OPERATOR_IMAGE);
            createAndMountImageDigest(PULSAR_IMAGE);
        }


        container.start();

        container.kubectl().start();

        final ExecutorService executorService = Executors.newFixedThreadPool(3);
        try {
            CompletableFuture.allOf(
                    CompletableFuture.runAsync(new Runnable() {
                        @Override
                        @SneakyThrows
                        public void run() {

                            final KubectlContainer kubectl = container.kubectl();
                            kubectl.followOutput((Consumer<OutputFrame>) outputFrame -> {
                                if (DEBUG_LOG_CONTAINER) {
                                    log.debug("kubectl > {}", outputFrame.getUtf8String());
                                }
                            });

                            kubectlApply("""
                                    apiVersion: v1
                                    kind: Namespace
                                    metadata:
                                      name: %s
                                    """.formatted(NAMESPACE));
                            kubectl.delete.namespace(NAMESPACE).force().ignoreNotFound().run("all", "--all");

                        }
                    }, executorService),
                    CompletableFuture.runAsync(() -> restoreDockerImageInK3s(OPERATOR_IMAGE), executorService),
                    CompletableFuture.runAsync(() -> restoreDockerImageInK3s(PULSAR_IMAGE), executorService)
            ).exceptionally(new Function<Throwable, Void>() {
                @Override
                public Void apply(Throwable throwable) {
                    throw new RuntimeException(throwable);
                }
            }).join();
        } finally {
            executorService.shutdown();
        }

        if (containerWasNull) {
            container.followOutput((Consumer<OutputFrame>) outputFrame -> {
                if (DEBUG_LOG_CONTAINER) {
                    log.debug("k3s > {}", outputFrame.getUtf8String());
                }
            });

        }
        printDebugInfo();
        client = new DefaultKubernetesClient(Config.fromKubeconfig(container.getKubeconfig()));
        eventsWatch = client.resources(Event.class).watch(new Watcher<Event>() {
            @Override
            public void eventReceived(Action action, Event resource) {
                log.info("event [{} - {}]: {} - {}", resource.getRegarding().getName(),
                        resource.getRegarding().getKind(),
                        resource.getNote(), resource.getReason());
            }

            @Override
            public void onClose(WatcherException cause) {
            }
        });
    }

    public static String getTmpKubeConfig(K3sContainer container) throws IOException {
        File tmpKubeConfig = File.createTempFile("test-kubeconfig", ".yaml");
        tmpKubeConfig.deleteOnExit();
        Files.write(tmpKubeConfig.toPath(), container.getKubeconfig().getBytes(StandardCharsets.UTF_8));
        return tmpKubeConfig.getAbsolutePath();
    }

    protected void printDebugInfo() throws IOException {
        log.info("export KUBECONFIG={}", getTmpKubeConfig(container));
    }

    protected void applyOperatorManifests() throws IOException, ExecutionException, InterruptedException {
        for (String yamlManifest : getYamlManifests()) {
            container.kubectl().copyFileToContainer(MountableFile.forClasspathResource(yamlManifest),
                    yamlManifest);
            container.kubectl().apply.from(yamlManifest).namespace(NAMESPACE).run();
            log.info("Applied {}", yamlManifest);
        }
    }

    protected void applyRBACManifests() {
        kubectlApply("""
                apiVersion: v1
                kind: ServiceAccount
                metadata:
                  name: pulsar-operator
                  namespace: %s
                ---
                apiVersion: rbac.authorization.k8s.io/v1
                kind: ClusterRole
                metadata:
                  name: pulsar-operator-role-admin
                  namespace: %s
                rules:
                  - apiGroups:
                      - apps
                    resources:
                      - deployments
                      - daemonsets
                      - replicasets
                      - statefulsets
                    verbs:
                      - "*"
                  - apiGroups:
                    - ""
                    resources:
                    - pods
                    - configmaps
                    - services
                    verbs:
                    - '*'
                  - apiGroups:
                    - "batch"
                    resources:
                    - jobs
                    verbs:
                    - '*'
                  - apiGroups:
                    - policy
                    resources:
                    - poddisruptionbudgets
                    verbs:
                    - "*"
                ---
                apiVersion: rbac.authorization.k8s.io/v1
                kind: ClusterRoleBinding
                metadata:
                  name: pulsar-operator-role-admin-binding
                  namespace: %s
                roleRef:
                  kind: ClusterRole
                  apiGroup: rbac.authorization.k8s.io
                  name: pulsar-operator-role-admin
                subjects:
                  - kind: ServiceAccount
                    name: pulsar-operator
                    namespace: %s
                """.formatted(NAMESPACE, NAMESPACE, NAMESPACE, NAMESPACE));
    }

    @SneakyThrows
    protected void deleteRBACManifests() {
        container.kubectl().delete.ignoreNotFound().namespace(NAMESPACE)
                .run("ServiceAccount", "pulsar-operator");

        container.kubectl().delete.ignoreNotFound().namespace(NAMESPACE)
                .run("ClusterRoleBinding", "pulsar-operator-role-admin-binding");

        container.kubectl().delete.ignoreNotFound().namespace(NAMESPACE)
                .run("ClusterRole", "pulsar-operator-role-admin");
    }


    private void createAndMountImageDigest(String image) {
        createAndMountImageDigest(image, container);
    }

    @SneakyThrows
    protected static void createAndMountImageDigest(String image, GenericContainer container) {
        String imageFilename;
        try {
            imageFilename = getMountedImageFilename(hostDockerClient, image);
        } catch (com.github.dockerjava.api.exception.NotFoundException notFoundException) {
            log.info("image {} not found locally, pulling..", image);
            final String[] split = image.split(":");
            hostDockerClient.pullImageCmd(split[0])
                    .withTag(split[1])
                    .start().awaitCompletion();
            log.info("image {} pulled", image);
            imageFilename = getMountedImageFilename(hostDockerClient, image);
        }

        final Path imageBinPath = Paths.get("target", imageFilename);
        if (imageBinPath.toFile().exists()) {
            log.info("Local image {} digest already exists, reusing it", image);
        } else {
            long start = System.currentTimeMillis();
            log.info("Local image {} digest not found in {}, generating", image,
                    imageBinPath.toFile().getAbsolutePath());
            final InputStream saved = hostDockerClient.saveImageCmd(image).exec();
            Files.copy(saved, imageBinPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("Local image {} digest generated in {} ms", image, (System.currentTimeMillis() - start));
        }
        container.withFileSystemBind(imageBinPath.toFile().getAbsolutePath(), "/" + imageFilename);
    }

    private static String getMountedImageFilename(DockerClient dockerClient, String image) {
        final String dockerImageId = dockerClient.inspectImageCmd(image).exec()
                .getId()
                .replace("sha256:", "");

        return "docker-digest-" + dockerImageId + ".bin";
    }

    private void restoreDockerImageInK3s(String imageName) {
        restoreDockerImageInK3s(imageName, container);
    }

    @SneakyThrows
    protected static void restoreDockerImageInK3s(String imageName, GenericContainer container) {
        log.info("Restoring docker image {} in k3s", imageName);
        long start = System.currentTimeMillis();
        final String mountedImageFilename = getMountedImageFilename(hostDockerClient, imageName);
        if (container.execInContainer("ctr", "images", "list").getStdout().contains(imageName)) {
            log.info("Image {} already exists in the k3s", imageName);
            return;
        }

        final Container.ExecResult execResult =
                container.execInContainer("ctr", "-a", "/run/k3s/containerd/containerd.sock",
                        "image", "import", mountedImageFilename);
        if (execResult.getExitCode() != 0) {
            throw new RuntimeException("ctr images import failed: " + execResult.getStderr());
        }
        log.info("Restored docker image {} in {} ms", imageName, (System.currentTimeMillis() - start));
    }


    @AfterClass(alwaysRun = true)
    public void afterClass() throws Exception {
        if (!DEBUG_CONTAINER_KEEP && container != null) {
            container.close();
            container = null;
        }
    }

    @AfterMethod(alwaysRun = true)
    public void after() throws Exception {
        printOperatorPodLogs();
        if (container != null) {
            deleteRBACManifests();
        }
        if (!RECYCLE_CONTAINER_SAME_TEST_CLASS && container != null) {
            container.close();
            container = null;
        }
        if (eventsWatch != null) {
            eventsWatch.close();
        }
        if (client != null) {
            client.close();
            client = null;
        }
    }

    private void printOperatorPodLogs() {
        if (operatorPodName != null) {
            try {
                final String podLog = client.pods().inNamespace(NAMESPACE)
                        .withName(operatorPodName)
                        .tailingLines(30)
                        .getLog();
                log.info("operator pod logs:\n{}", podLog);
            } catch (Throwable t) {
                log.error("failed to get operator pod logs: {}", t.getMessage(), t);
            }
        }
    }

    @SneakyThrows
    protected void kubectlApply(Path path) {
        final String outputFilename = "/tmp/manifest" + System.nanoTime() + ".yml";
        container.kubectl().copyFileToContainer(MountableFile.forHostPath(path),
                outputFilename);
        container.kubectl().apply.from(outputFilename).namespace(NAMESPACE).run();
    }

    @SneakyThrows
    protected void kubectlApply(String manifest) {
        final String outputFilename = "/tmp/manifest" + System.nanoTime() + ".yml";
        container.kubectl().copyFileToContainer(Transferable.of(manifest),
                outputFilename);
        container.kubectl().apply.from(outputFilename).namespace(NAMESPACE).run();
    }


    @SneakyThrows
    protected void helmInstall() {
        final Path helmHome = Paths.get("..", "helm", "pulsar-operator");


        container.beforeStartHelm3((Consumer<Helm3Container>) helm3Container -> {
            helm3Container.withFileSystemBind(helmHome.toFile().getAbsolutePath(), "/helm-pulsar-operator");
        });

        final Helm3Container helm3Container = container.helm3();

        helm3Container.execInContainer("helm", "delete", "test", "-n", NAMESPACE);
        final String cmd = "helm install test -n " + NAMESPACE + " /helm-pulsar-operator";
        final Container.ExecResult exec = helm3Container.execInContainer(cmd.split(" "));
        if (exec.getExitCode() != 0) {
            throw new RuntimeException("Helm installation failed: " + exec.getStderr());
        }
    }

    @SneakyThrows
    protected void helmUninstall() {
        final Helm3Container helm3Container = container.helm3();
        final Container.ExecResult exec =
                helm3Container.execInContainer("helm", "delete", "test", "-n", NAMESPACE);
        if (exec.getExitCode() != 0) {
            throw new RuntimeException("Helm uninstallation failed: " + exec.getStderr());
        }
    }

    @SneakyThrows
    protected Path getHelmExampleFilePath(String name) {
        return Paths.get("..", "helm", "examples", name);
    }


    protected void awaitOperatorRunning() {
        Awaitility.await().pollInterval(1, TimeUnit.SECONDS).untilAsserted(() -> {
            final List<Pod> pods = client.pods()
                    .withLabel("app.kubernetes.io/name", "pulsar-operator").list().getItems();
            Assert.assertEquals(pods.size(), 1);
            Assert.assertEquals(pods.stream().filter(p -> p.getStatus().getPhase().equals("Running"))
                    .count(), 1);
        });
        operatorPodName = client.pods()
                .withLabel("app.kubernetes.io/name", "pulsar-operator")
                .list()
                .getItems()
                .get(0)
                .getMetadata()
                .getName();
    }

}