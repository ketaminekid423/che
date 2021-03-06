/*
 * Copyright (c) 2012-2018 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package org.eclipse.che.workspace.infrastructure.openshift.provision;

import static com.google.common.collect.ImmutableMap.of;
import static java.lang.Boolean.parseBoolean;
import static java.lang.String.format;
import static java.lang.String.valueOf;
import static java.util.Collections.singletonList;
import static org.eclipse.che.api.workspace.shared.Constants.ASYNC_PERSIST_ATTRIBUTE;
import static org.eclipse.che.workspace.infrastructure.kubernetes.Constants.CHE_USER_ID_LABEL;
import static org.eclipse.che.workspace.infrastructure.kubernetes.Warnings.NOT_ABLE_TO_PROVISION_SSH_KEYS;
import static org.eclipse.che.workspace.infrastructure.kubernetes.Warnings.NOT_ABLE_TO_PROVISION_SSH_KEYS_MESSAGE;
import static org.eclipse.che.workspace.infrastructure.kubernetes.namespace.pvc.CommonPVCStrategy.COMMON_STRATEGY;
import static org.eclipse.che.workspace.infrastructure.kubernetes.namespace.pvc.EphemeralWorkspaceUtility.isEphemeral;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ConfigMapVolumeSourceBuilder;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ContainerPortBuilder;
import io.fabric8.kubernetes.api.model.DoneableConfigMap;
import io.fabric8.kubernetes.api.model.DoneablePersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.DoneablePod;
import io.fabric8.kubernetes.api.model.DoneableService;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.IntOrStringBuilder;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimVolumeSourceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.PodSpecBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.ServicePortBuilder;
import io.fabric8.kubernetes.api.model.ServiceSpec;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.ServiceResource;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Named;
import org.eclipse.che.api.core.ConflictException;
import org.eclipse.che.api.core.ServerException;
import org.eclipse.che.api.core.model.workspace.runtime.RuntimeIdentity;
import org.eclipse.che.api.ssh.server.SshManager;
import org.eclipse.che.api.ssh.server.model.impl.SshPairImpl;
import org.eclipse.che.api.ssh.shared.model.SshPair;
import org.eclipse.che.api.workspace.server.model.impl.WarningImpl;
import org.eclipse.che.api.workspace.server.spi.InfrastructureException;
import org.eclipse.che.workspace.infrastructure.kubernetes.Names;
import org.eclipse.che.workspace.infrastructure.kubernetes.namespace.KubernetesObjectUtil;
import org.eclipse.che.workspace.infrastructure.openshift.OpenShiftClientFactory;
import org.eclipse.che.workspace.infrastructure.openshift.environment.OpenShiftEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configure environment for Async Storage feature (details described in issue
 * https://github.com/eclipse/che/issues/15384) This environment will allow backup on workspace stop
 * event and restore on restart created earlier. <br>
 * Will apply only in case workspace has attributes: asyncPersist: true - persistVolumes:
 * false.</br> In case workspace has attributes: asyncPersist: true - persistVolumes: true will
 * throw exception.</br> Feature enabled only for 'common' PVC strategy, in other cases will throw
 * exception.</br> During provision will be created: - storage Pod - service for rsync connection
 * via SSH - configmap, with public part of SSH key - PVC for storing backups;
 */
public class AsyncStorageProvisioner {

  private static final int SERVICE_PORT = 2222;
  /**
   * The authorized_keys file in SSH specifies the SSH keys that can be used for logging into the
   * user account for which the file is configured.
   */
  private static final String AUTHORIZED_KEYS = "authorized_keys";
  /**
   * Name of the asynchronous storage Pod and Service. Rsync command will use this Service name for
   * communications: e.g.: rsync ${RSYNC_OPTIONS} --rsh="ssh ${SSH_OPTIONS}" async-storage:/{PATH}
   */
  static final String ASYNC_STORAGE = "async-storage";
  /** The name suffix for ConfigMap with SSH configuration */
  static final String ASYNC_STORAGE_CONFIG = "async-storage-config";
  /** The path of mount storage volume for file persist */
  private static final String ASYNC_STORAGE_DATA_PATH = "/" + ASYNC_STORAGE;
  /** The path to the authorized_keys */
  private static final String SSH_KEY_PATH = "/.ssh/" + AUTHORIZED_KEYS;
  /** The name of SSH key pair for rsync */
  static final String SSH_KEY_NAME = "rsync-via-ssh";
  /** The name of volume for mounting configuration map and authorized_keys */
  private static final String CONFIG_MAP_VOLUME_NAME = "async-storage-configvolume";
  /** */
  private static final String STORAGE_VOLUME = "async-storage-data";

  private static final Logger LOG = LoggerFactory.getLogger(AsyncStorageProvisioner.class);

  private final String sidecarImagePullPolicy;
  private final String pvcQuantity;
  private final String asyncStorageImage;
  private final String pvcAccessMode;
  private final String pvcStrategy;
  private final String pvcName;
  private final String pvcStorageClassName;
  private final SshManager sshManager;
  private final OpenShiftClientFactory clientFactory;

  @Inject
  public AsyncStorageProvisioner(
      @Named("che.workspace.sidecar.image_pull_policy") String sidecarImagePullPolicy,
      @Named("che.infra.kubernetes.pvc.quantity") String pvcQuantity,
      @Named("che.infra.kubernetes.async.storage.image") String asyncStorageImage,
      @Named("che.infra.kubernetes.pvc.access_mode") String pvcAccessMode,
      @Named("che.infra.kubernetes.pvc.strategy") String pvcStrategy,
      @Named("che.infra.kubernetes.pvc.name") String pvcName,
      @Named("che.infra.kubernetes.pvc.storage_class_name") String pvcStorageClassName,
      SshManager sshManager,
      OpenShiftClientFactory openShiftClientFactory) {
    this.sidecarImagePullPolicy = sidecarImagePullPolicy;
    this.pvcQuantity = pvcQuantity;
    this.asyncStorageImage = asyncStorageImage;
    this.pvcAccessMode = pvcAccessMode;
    this.pvcStrategy = pvcStrategy;
    this.pvcName = pvcName;
    this.pvcStorageClassName = pvcStorageClassName;
    this.sshManager = sshManager;
    this.clientFactory = openShiftClientFactory;
  }

  public void provision(OpenShiftEnvironment osEnv, RuntimeIdentity identity)
      throws InfrastructureException {
    if (!parseBoolean(osEnv.getAttributes().get(ASYNC_PERSIST_ATTRIBUTE))) {
      return;
    }

    if (!COMMON_STRATEGY.equals(pvcStrategy)) {
      String message =
          format(
              "Workspace configuration not valid: Asynchronous storage available only for 'common' PVC strategy, but got %s",
              pvcStrategy);
      LOG.warn(message);
      osEnv.addWarning(new WarningImpl(4200, message));
      throw new InfrastructureException(message);
    }

    if (!isEphemeral(osEnv.getAttributes())) {
      String message =
          format(
              "Workspace configuration not valid: Asynchronous storage available only if '%s' attribute set to false",
              ASYNC_PERSIST_ATTRIBUTE);
      LOG.warn(message);
      osEnv.addWarning(new WarningImpl(4200, message));
      throw new InfrastructureException(message);
    }

    String namespace = identity.getInfrastructureNamespace();
    String userId = identity.getOwnerId();
    KubernetesClient oc = clientFactory.create(identity.getWorkspaceId());
    String configMapName = namespace + ASYNC_STORAGE_CONFIG;

    createPvcIfNotExist(oc, namespace, userId);
    createConfigMapIfNotExist(oc, namespace, configMapName, userId, osEnv);
    createAsyncStoragePodIfNotExist(oc, namespace, configMapName, userId);
    createStorageServiceIfNotExist(oc, namespace, userId);
  }

  private void createPvcIfNotExist(KubernetesClient oc, String namespace, String userId) {
    Resource<PersistentVolumeClaim, DoneablePersistentVolumeClaim> claimResource =
        oc.persistentVolumeClaims().inNamespace(namespace).withName(pvcName);

    if (claimResource.get() != null) {
      return; // pvc already exist
    }
    PersistentVolumeClaim pvc =
        KubernetesObjectUtil.newPVC(pvcName, pvcAccessMode, pvcQuantity, pvcStorageClassName);
    KubernetesObjectUtil.putLabel(pvc.getMetadata(), CHE_USER_ID_LABEL, userId);
    oc.persistentVolumeClaims().inNamespace(namespace).create(pvc);
  }

  /** Get or create new pair of SSH keys, this is need for securing rsync connection */
  private List<SshPairImpl> getOrCreateSshPairs(String userId, OpenShiftEnvironment osEnv)
      throws InfrastructureException {
    List<SshPairImpl> sshPairs;
    try {
      sshPairs = sshManager.getPairs(userId, "internal");
    } catch (ServerException e) {
      String message = format("Unable to get SSH Keys. Cause: %s", e.getMessage());
      LOG.warn(message);
      osEnv.addWarning(
          new WarningImpl(
              NOT_ABLE_TO_PROVISION_SSH_KEYS,
              format(NOT_ABLE_TO_PROVISION_SSH_KEYS_MESSAGE, message)));
      throw new InfrastructureException(e);
    }
    if (sshPairs.isEmpty()) {
      try {
        sshPairs = singletonList(sshManager.generatePair(userId, "internal", SSH_KEY_NAME));
      } catch (ServerException | ConflictException e) {
        String message =
            format(
                "Unable to generate the SSH key for async storage service. Cause: %S",
                e.getMessage());
        LOG.warn(message);
        osEnv.addWarning(
            new WarningImpl(
                NOT_ABLE_TO_PROVISION_SSH_KEYS,
                format(NOT_ABLE_TO_PROVISION_SSH_KEYS_MESSAGE, message)));
        throw new InfrastructureException(e);
      }
    }
    return sshPairs;
  }

  /** Create configmap with public part of SSH key */
  private void createConfigMapIfNotExist(
      KubernetesClient oc,
      String namespace,
      String configMapName,
      String userId,
      OpenShiftEnvironment osEnv)
      throws InfrastructureException {
    Resource<ConfigMap, DoneableConfigMap> mapResource =
        oc.configMaps().inNamespace(namespace).withName(configMapName);
    if (mapResource.get() != null) { // map already exist
      return;
    }

    List<SshPairImpl> sshPairs = getOrCreateSshPairs(userId, osEnv);
    if (sshPairs == null) {
      return;
    }
    SshPair sshPair = sshPairs.get(0);
    Map<String, String> sshConfigData = of(AUTHORIZED_KEYS, sshPair.getPublicKey() + "\n");
    ConfigMap configMap =
        new ConfigMapBuilder()
            .withNewMetadata()
            .withName(configMapName)
            .withNamespace(namespace)
            .withLabels(of(CHE_USER_ID_LABEL, userId))
            .endMetadata()
            .withData(sshConfigData)
            .build();
    oc.configMaps().inNamespace(namespace).create(configMap);
  }

  /**
   * Create storage Pod with container with mounted volume for storing project source backups, SSH
   * key and exposed port for rsync connection
   */
  private void createAsyncStoragePodIfNotExist(
      KubernetesClient oc, String namespace, String configMap, String userId) {
    PodResource<Pod, DoneablePod> podResource =
        oc.pods().inNamespace(namespace).withName(ASYNC_STORAGE);
    if (podResource.get() != null) {
      return; // pod already exist
    }

    String containerName = Names.generateName(ASYNC_STORAGE);

    Volume storageVolume =
        new VolumeBuilder()
            .withName(STORAGE_VOLUME)
            .withPersistentVolumeClaim(
                new PersistentVolumeClaimVolumeSourceBuilder()
                    .withClaimName(pvcName)
                    .withReadOnly(false)
                    .build())
            .build();

    Volume sshKeyVolume =
        new VolumeBuilder()
            .withName(CONFIG_MAP_VOLUME_NAME)
            .withConfigMap(
                new ConfigMapVolumeSourceBuilder()
                    .withName(configMap)
                    .withDefaultMode(0600)
                    .build())
            .build();

    VolumeMount storageVolumeMount =
        new VolumeMountBuilder()
            .withMountPath(ASYNC_STORAGE_DATA_PATH)
            .withName(STORAGE_VOLUME)
            .withReadOnly(false)
            .build();

    VolumeMount sshVolumeMount =
        new VolumeMountBuilder()
            .withMountPath(SSH_KEY_PATH)
            .withSubPath(AUTHORIZED_KEYS)
            .withName(CONFIG_MAP_VOLUME_NAME)
            .withReadOnly(true)
            .build();

    Container container =
        new ContainerBuilder()
            .withName(containerName)
            .withImage(asyncStorageImage)
            .withImagePullPolicy(sidecarImagePullPolicy)
            .withNewResources()
            .addToLimits("memory", new Quantity("512Mi"))
            .addToRequests("memory", new Quantity("256Mi"))
            .endResources()
            .withPorts(
                new ContainerPortBuilder()
                    .withContainerPort(SERVICE_PORT)
                    .withProtocol("TCP")
                    .build())
            .withVolumeMounts(storageVolumeMount, sshVolumeMount)
            .build();

    PodSpecBuilder podSpecBuilder = new PodSpecBuilder();
    PodSpec podSpec =
        podSpecBuilder.withContainers(container).withVolumes(storageVolume, sshKeyVolume).build();

    Pod pod =
        new PodBuilder()
            .withApiVersion("v1")
            .withKind("Pod")
            .withNewMetadata()
            .withName(ASYNC_STORAGE)
            .withNamespace(namespace)
            .withLabels(of("app", ASYNC_STORAGE, CHE_USER_ID_LABEL, userId))
            .endMetadata()
            .withSpec(podSpec)
            .build();

    oc.pods().inNamespace(namespace).create(pod);
  }

  /** Create service for serving rsync connection */
  private void createStorageServiceIfNotExist(
      KubernetesClient oc, String namespace, String userId) {
    ServiceResource<Service, DoneableService> serviceResource =
        oc.services().inNamespace(namespace).withName(ASYNC_STORAGE);
    if (serviceResource.get() != null) {
      return; // service already exist
    }

    ObjectMeta meta = new ObjectMeta();
    meta.setName(ASYNC_STORAGE);
    meta.setNamespace(namespace);
    meta.setLabels(of(CHE_USER_ID_LABEL, userId));

    IntOrString targetPort =
        new IntOrStringBuilder().withIntVal(SERVICE_PORT).withStrVal(valueOf(SERVICE_PORT)).build();

    ServicePort port =
        new ServicePortBuilder()
            .withName("rsync-port")
            .withProtocol("TCP")
            .withPort(SERVICE_PORT)
            .withTargetPort(targetPort)
            .build();
    ServiceSpec spec = new ServiceSpec();
    spec.setPorts(singletonList(port));
    spec.setSelector(of("app", ASYNC_STORAGE));

    Service service = new Service();
    service.setApiVersion("v1");
    service.setKind("Service");
    service.setMetadata(meta);
    service.setSpec(spec);

    oc.services().inNamespace(namespace).create(service);
  }
}
