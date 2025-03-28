/*
 * Copyright 2019 The gRPC Authors
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

package io.grpc.xds.internal.security.trust;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import io.envoyproxy.envoy.config.core.v3.DataSource.SpecifierCase;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CertificateValidationContext;
import io.netty.handler.ssl.util.SimpleTrustManagerFactory;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertStoreException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.ManagerFactoryParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * Factory class used to provide a {@link XdsX509TrustManager} for trust and SAN checks.
 */
public final class XdsTrustManagerFactory extends SimpleTrustManagerFactory {

  private static final Logger logger = Logger.getLogger(XdsTrustManagerFactory.class.getName());
  private XdsX509TrustManager xdsX509TrustManager;

  /** Constructor constructs from a {@link CertificateValidationContext}. */
  public XdsTrustManagerFactory(CertificateValidationContext certificateValidationContext)
      throws CertificateException, IOException, CertStoreException {
    this(
        getTrustedCaFromCertContext(certificateValidationContext),
        certificateValidationContext,
        false);
  }

  public XdsTrustManagerFactory(
          X509Certificate[] certs, CertificateValidationContext staticCertificateValidationContext)
          throws CertStoreException {
    this(certs, staticCertificateValidationContext, true);
  }

  public XdsTrustManagerFactory(Map<String, List<X509Certificate>> spiffeTrustMap,
      CertificateValidationContext staticCertificateValidationContext) throws CertStoreException {
    this(spiffeTrustMap, staticCertificateValidationContext, true);
  }

  private XdsTrustManagerFactory(
      X509Certificate[] certs,
      CertificateValidationContext certificateValidationContext,
      boolean validationContextIsStatic)
      throws CertStoreException {
    if (validationContextIsStatic) {
      checkArgument(
          certificateValidationContext == null || !certificateValidationContext.hasTrustedCa(),
          "only static certificateValidationContext expected");
    }
    xdsX509TrustManager = createX509TrustManager(certs, certificateValidationContext);
  }

  private XdsTrustManagerFactory(
      Map<String, List<X509Certificate>> spiffeTrustMap,
      CertificateValidationContext certificateValidationContext,
      boolean validationContextIsStatic)
      throws CertStoreException {
    if (validationContextIsStatic) {
      checkArgument(
          certificateValidationContext == null || !certificateValidationContext.hasTrustedCa(),
          "only static certificateValidationContext expected");
      xdsX509TrustManager = createX509TrustManager(spiffeTrustMap, certificateValidationContext);
    }
  }

  private static X509Certificate[] getTrustedCaFromCertContext(
      CertificateValidationContext certificateValidationContext)
      throws CertificateException, IOException {
    final SpecifierCase specifierCase =
        certificateValidationContext.getTrustedCa().getSpecifierCase();
    if (specifierCase == SpecifierCase.FILENAME) {
      String certsFile = certificateValidationContext.getTrustedCa().getFilename();
      checkState(
          !Strings.isNullOrEmpty(certsFile),
          "trustedCa.file-name in certificateValidationContext cannot be empty");
      return CertificateUtils.toX509Certificates(new File(certsFile));
    } else if (specifierCase == SpecifierCase.INLINE_BYTES) {
      try (InputStream is =
          certificateValidationContext.getTrustedCa().getInlineBytes().newInput()) {
        return CertificateUtils.toX509Certificates(is);
      }
    } else {
      throw new IllegalArgumentException("Not supported: " + specifierCase);
    }
  }

  @VisibleForTesting
  static XdsX509TrustManager createX509TrustManager(
      X509Certificate[] certs, CertificateValidationContext certContext) throws CertStoreException {
    return new XdsX509TrustManager(certContext, createTrustManager(certs));
  }

  @VisibleForTesting
  static XdsX509TrustManager createX509TrustManager(
      Map<String, List<X509Certificate>> spiffeTrustMapFile,
      CertificateValidationContext certContext) throws CertStoreException {
    checkNotNull(spiffeTrustMapFile, "spiffeTrustMapFile");
    Map<String, X509ExtendedTrustManager> delegates = new HashMap<>();
    for (Map.Entry<String, List<X509Certificate>> entry:spiffeTrustMapFile.entrySet()) {
      delegates.put(entry.getKey(), createTrustManager(
          entry.getValue().toArray(new X509Certificate[0])));
    }
    return new XdsX509TrustManager(certContext, delegates);
  }

  private static X509ExtendedTrustManager createTrustManager(X509Certificate[] certs)
      throws CertStoreException {
    TrustManagerFactory tmf = null;
    try {
      tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      KeyStore ks = KeyStore.getInstance("PKCS12");
      // perform a load to initialize KeyStore
      ks.load(/* stream= */ null, /* password= */ null);
      int i = 1;
      for (X509Certificate cert : certs) {
        // note: alias lookup uses toLowerCase(Locale.ENGLISH)
        // so our alias needs to be all lower-case and unique
        ks.setCertificateEntry("alias" + i, cert);
        i++;
      }
      tmf.init(ks);
    } catch (NoSuchAlgorithmException | KeyStoreException | IOException | CertificateException e) {
      logger.log(Level.SEVERE, "createX509TrustManager", e);
      throw new CertStoreException(e);
    }
    TrustManager[] tms = tmf.getTrustManagers();
    X509ExtendedTrustManager myDelegate = null;
    if (tms != null) {
      for (TrustManager tm : tms) {
        if (tm instanceof X509ExtendedTrustManager) {
          myDelegate = (X509ExtendedTrustManager) tm;
          break;
        }
      }
    }
    if (myDelegate == null) {
      throw new CertStoreException("Native X509 TrustManager not found.");
    }
    return myDelegate;
  }

  @Override
  protected void engineInit(KeyStore keyStore) throws Exception {
    throw new UnsupportedOperationException();
  }

  @Override
  protected void engineInit(ManagerFactoryParameters managerFactoryParameters) throws Exception {
    throw new UnsupportedOperationException();
  }

  @Override
  protected TrustManager[] engineGetTrustManagers() {
    return new TrustManager[] {xdsX509TrustManager};
  }
}
