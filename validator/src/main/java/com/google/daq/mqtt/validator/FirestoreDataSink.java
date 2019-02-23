package com.google.daq.mqtt.validator;

import com.google.auth.Credentials;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.ServiceOptions;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import com.google.common.base.Preconditions;
import com.google.daq.mqtt.validator.ExceptionMap.ErrorTree;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

class FirestoreDataSink {

  private static final String
      CREDENTIAL_ERROR_FORMAT = "Credential file %s defined by %s not found.";
  private static final String
      VIEW_URL_FORMAT = "https://console.cloud.google.com/firestore/data/registries/?project=%s";

  private static final DateTimeFormatter dateTimeFormatter =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX").withZone(ZoneOffset.UTC);

  private final Firestore db;
  private final String projectId = ServiceOptions.getDefaultProjectId();

  private final AtomicReference<RuntimeException> oldError = new AtomicReference<>();

  FirestoreDataSink() {
    try {
      Credentials projectCredentials = getProjectCredentials();
      FirestoreOptions firestoreOptions =
          FirestoreOptions.getDefaultInstance().toBuilder()
              .setCredentials(projectCredentials)
              .setProjectId(projectId)
              .setTimestampsInSnapshotsEnabled(true)
              .build();

      db = firestoreOptions.getService();
    } catch (Exception e) {
      throw new RuntimeException("While creating Firestore connection to " + projectId, e);
    }
  }

  private Credentials getProjectCredentials() throws IOException {
    File credentialFile = new File(System.getenv(ServiceOptions.CREDENTIAL_ENV_NAME));
    if (!credentialFile.exists()) {
      throw new RuntimeException(String.format(CREDENTIAL_ERROR_FORMAT,
          credentialFile.getAbsolutePath(), ServiceOptions.CREDENTIAL_ENV_NAME));
    }
    try (FileInputStream serviceAccount = new FileInputStream(credentialFile)) {
      return GoogleCredentials.fromStream(serviceAccount);
    }
  }

  void validationResult(String deviceId, String schemaId, Map<String, String> attributes,
      Object message,
      ErrorTree errorTree) {
    if (oldError.get() != null) {
      throw oldError.getAndSet(null);
    }

    try {
      String registryId = attributes.get("deviceRegistryId");
      Preconditions.checkNotNull(deviceId, "deviceId attribute not defined");
      Preconditions.checkNotNull(schemaId, "schemaId not properly defined");
      Preconditions.checkNotNull(registryId, "deviceRegistryId attribute not defined");
      String instantNow = dateTimeFormatter.format(Instant.now());
      DocumentReference registryDoc = db.collection("registries").document(registryId);
      registryDoc.update("validated", instantNow);
      DocumentReference deviceDoc = registryDoc.collection("devices").document(deviceId);
      deviceDoc.update("validated", instantNow);
      DocumentReference resultDoc = deviceDoc.collection("validations").document(schemaId);
      PojoBundle dataBundle = new PojoBundle();
      dataBundle.validated = instantNow;
      dataBundle.errorTree = errorTree;
      dataBundle.attributes = attributes;
      dataBundle.message = message;
      resultDoc.set(dataBundle);
    } catch (Exception e) {
      throw new RuntimeException("While writing result for " + deviceId, e);
    }
  }

  static class PojoBundle {
    public String validated;
    public ErrorTree errorTree;
    public Object message;
    public Map<String, String> attributes;
  }

  String getViewUrl() {
    return String.format(VIEW_URL_FORMAT, projectId);
  }
}
