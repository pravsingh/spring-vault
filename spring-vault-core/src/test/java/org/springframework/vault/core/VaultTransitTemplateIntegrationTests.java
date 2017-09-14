/*
 * Copyright 2016-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.vault.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.util.Base64Utils;
import org.springframework.util.StringUtils;
import org.springframework.vault.VaultException;
import org.springframework.vault.support.VaultMount;
import org.springframework.vault.support.VaultTransitContext;
import org.springframework.vault.support.VaultTransitKey;
import org.springframework.vault.support.VaultTransitKeyConfiguration;
import org.springframework.vault.support.VaultTransitKeyCreationRequest;
import org.springframework.vault.util.IntegrationTestSupport;
import org.springframework.vault.util.Version;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.Assume.assumeTrue;

/**
 * Integration tests for {@link VaultTransitTemplate} through
 * {@link VaultTransitOperations}.
 *
 * @author Mark Paluch
 */
@RunWith(SpringRunner.class)
@ContextConfiguration(classes = VaultIntegrationTestConfiguration.class)
public class VaultTransitTemplateIntegrationTests extends IntegrationTestSupport {

	private static final String BATCH_SUPPORTED_VERSION = "0.6.5";

	@Autowired
	private VaultOperations vaultOperations;
	private VaultTransitOperations transitOperations;

	@Before
	public void before() {

		transitOperations = vaultOperations.opsForTransit();

		if (!vaultOperations.opsForSys().getMounts().containsKey("transit/")) {
			vaultOperations.opsForSys().mount("transit", VaultMount.create("transit"));
		}

		removeKeys();
	}

	@After
	public void tearDown() {
		removeKeys();
	}

	private void deleteKey(String keyName) {

		try {
			transitOperations.configureKey(keyName, VaultTransitKeyConfiguration
					.builder().deletionAllowed(true).build());
		}
		catch (Exception e) {
		}

		try {
			transitOperations.deleteKey(keyName);
		}
		catch (Exception e) {
		}
	}

	private void removeKeys() {

		if (prepare().getVersion().isGreaterThanOrEqualTo(Version.parse("0.6.4"))) {
			List<String> keys = vaultOperations.opsForTransit().getKeys();
			for (String keyName : keys) {
				deleteKey(keyName);
			}
		}
		else {
			deleteKey("mykey");
			deleteKey("derived");
		}
	}
	
	@Test
	public void createKeyShouldCreateKey() {

		transitOperations.createKey("mykey");

		VaultTransitKey mykey = transitOperations.getKey("mykey");

		assertThat(mykey.getType()).startsWith("aes");

		assertThat(mykey.getName()).isEqualTo("mykey");
		assertThat(mykey.isDeletionAllowed()).isFalse();
		assertThat(mykey.isDerived()).isFalse();
		assertThat(mykey.getMinDecryptionVersion()).isEqualTo(1);
		assertThat(mykey.isLatestVersion()).isTrue();
	}

	@Test
	public void createKeyShouldCreateKeyWithOptions() {

		VaultTransitKeyCreationRequest request = VaultTransitKeyCreationRequest.builder() //
				.convergentEncryption(true) //
				.derived(true) //
				.build();

		transitOperations.createKey("mykey", request);

		VaultTransitKey mykey = transitOperations.getKey("mykey");

		assertThat(mykey.getName()).isEqualTo("mykey");
		assertThat(mykey.isDeletionAllowed()).isFalse();
		assertThat(mykey.isDerived()).isTrue();
		assertThat(mykey.getMinDecryptionVersion()).isEqualTo(1);
		assertThat(mykey.isLatestVersion()).isTrue();
	}

	@Test
	public void shouldEnumerateKey() {

		assumeTrue(prepare().getVersion().isGreaterThanOrEqualTo(Version.parse("0.6.4")));

		assertThat(transitOperations.getKeys()).isEmpty();

		transitOperations.createKey("mykey");

		assertThat(transitOperations.getKeys()).contains("mykey");
	}

	@Test
	public void getKeyShouldReturnNullIfKeyNotExists() {

		VaultTransitKey key = transitOperations.getKey("hello-world");
		assertThat(key).isNull();
	}

	@Test
	public void deleteKeyShouldFailIfKeyNotExists() {

		try {
			transitOperations.deleteKey("hello-world");
			fail("Missing VaultException");
		}
		catch (VaultException e) {
			assertThat(e).hasMessageContaining("Status 400");
		}
	}

	@Test
	public void deleteKeyShouldDeleteKey() {

		transitOperations.createKey("mykey");
		transitOperations.configureKey("mykey", VaultTransitKeyConfiguration.builder()
				.deletionAllowed(true).build());
		transitOperations.deleteKey("mykey");

		assertThat(transitOperations.getKey("mykey")).isNull();
	}

	@Test
	public void encryptShouldCreateCiphertext() {

		transitOperations.createKey("mykey");

		String ciphertext = transitOperations.encrypt("mykey", "hello-world");
		assertThat(ciphertext).startsWith("vault:v");
	}

	@Test
	public void encryptShouldCreateCiphertextWithNonceAndContext() {

		transitOperations.createKey("mykey", VaultTransitKeyCreationRequest.builder()
				.convergentEncryption(true).derived(true).build());

		VaultTransitContext transitRequest = VaultTransitContext.builder()
				.context("blubb".getBytes()) //
				.nonce("123456789012".getBytes()) //
				.build();

		String ciphertext = transitOperations.encrypt("mykey", "hello-world".getBytes(),
				transitRequest);
		assertThat(ciphertext).startsWith("vault:v1:");
	}

	@Test
	public void decryptShouldCreatePlaintext() {

		transitOperations.createKey("mykey");

		String ciphertext = transitOperations.encrypt("mykey", "hello-world");
		String plaintext = transitOperations.decrypt("mykey", ciphertext);

		assertThat(plaintext).isEqualTo("hello-world");
	}

	@Test
	public void decryptShouldCreatePlaintextWithNonceAndContext() {

		transitOperations.createKey("mykey", VaultTransitKeyCreationRequest.builder()
				.convergentEncryption(true).derived(true).build());

		VaultTransitContext transitRequest = VaultTransitContext.builder() //
				.context("blubb".getBytes()) //
				.nonce("123456789012".getBytes()) //
				.build();

		String ciphertext = transitOperations.encrypt("mykey", "hello-world".getBytes(),
				transitRequest);

		byte[] plaintext = transitOperations.decrypt("mykey", ciphertext, transitRequest);
		assertThat(new String(plaintext)).isEqualTo("hello-world");
	}

	@Test
	public void encryptAndRewrapShouldCreateCiphertext() {

		transitOperations.createKey("mykey");

		String ciphertext = transitOperations.encrypt("mykey", "hello-world");
		transitOperations.rotate("mykey");

		String rewrapped = transitOperations.rewrap("mykey", ciphertext);

		assertThat(rewrapped).startsWith("vault:v2:");
	}

	@Test
	public void shouldEncryptBinaryPlaintext() {

		transitOperations.createKey("mykey");

		byte[] plaintext = new byte[] { 1, 2, 3, 4, 5 };

		String ciphertext = transitOperations.encrypt("mykey", plaintext,
				VaultTransitContext.empty());

		byte[] decrypted = transitOperations.decrypt("mykey", ciphertext,
				VaultTransitContext.empty());

		assertThat(decrypted).isEqualTo(plaintext);
	}

	@Test
	public void encryptAndRewrapShouldCreateCiphertextWithNonceAndContext() {

		transitOperations.createKey("mykey", VaultTransitKeyCreationRequest.builder()
				.convergentEncryption(true).derived(true).build());

		VaultTransitContext transitRequest = VaultTransitContext.builder() //
				.context("blubb".getBytes()) //
				.nonce("123456789012".getBytes()) //
				.build();

		String ciphertext = transitOperations.encrypt("mykey", "hello-world".getBytes(),
				transitRequest);
		transitOperations.rotate("mykey");

		String rewrapped = transitOperations.rewrap("mykey", ciphertext, transitRequest);
		assertThat(rewrapped).startsWith("vault:v2");
	}
	
	@Test
	public void batchEncryptionAndDecryptionTestWithoutContext() {

		if (prepare().getVersion().isLessThan(Version.parse(BATCH_SUPPORTED_VERSION))) {
			return;
		}
		
		transitOperations.createKey("mykey");

		List<String> plaintexts = new ArrayList<String>();
		plaintexts.add("one");
		plaintexts.add("two");

		batchEncryptionAndDecryption(plaintexts, null, null);
	}

	@Test
	public void batchEncryptionAndDecryptionTestWithMatchingContext() {

		if (prepare().getVersion().isLessThan(Version.parse(BATCH_SUPPORTED_VERSION))) {
			return;
		}
		
		VaultTransitKeyCreationRequest request = VaultTransitKeyCreationRequest.builder() //
				.derived(true) //
				.build();

		transitOperations.createKey("mykey", request);

		List<String> plaintexts = new ArrayList<String>();
		plaintexts.add("one");
		plaintexts.add("two");

		List<VaultTransitContext> contexts = new ArrayList<VaultTransitContext>();
		contexts.add(VaultTransitContext.builder().context("oneContext".getBytes()).build());
		contexts.add(VaultTransitContext.builder().context("twoContext".getBytes()).build());

		batchEncryptionAndDecryption(plaintexts, contexts, contexts);
	}

	@Test
	public void batchEncryptionAndDecryptionTestWithNonEqualContext() {

		if (prepare().getVersion().isLessThan(Version.parse(BATCH_SUPPORTED_VERSION))) {
			return;
		}

		try {

			VaultTransitKeyCreationRequest request = VaultTransitKeyCreationRequest.builder() //
					.derived(true) //
					.build();

			transitOperations.createKey("mykey", request);

			List<String> plaintexts = new ArrayList<String>();
			plaintexts.add("one");
			plaintexts.add("two");

			List<VaultTransitContext> encryptionContexts = new ArrayList<VaultTransitContext>();
			encryptionContexts.add(VaultTransitContext.builder().context("oneContext".getBytes()).build());
			encryptionContexts.add(VaultTransitContext.builder().context("twoContext".getBytes()).build());

			List<VaultTransitContext> decryptionContext = new ArrayList<VaultTransitContext>();
			decryptionContext.add(VaultTransitContext.builder().context("oneContext".getBytes()).build());

			batchEncryptionAndDecryption(plaintexts, encryptionContexts, decryptionContext);
		
		} catch (IllegalArgumentException e) {
			return;
		}

		Assert.fail();
	}

	@Test
	public void batchEncryptionAndDecryptionTestWithNonMatchingContext() {

		if (prepare().getVersion().isLessThan(Version.parse(BATCH_SUPPORTED_VERSION))) {
			return;
		}

		try {

			VaultTransitKeyCreationRequest request = VaultTransitKeyCreationRequest.builder() //
					.derived(true) //
					.build();

			transitOperations.createKey("mykey", request);

			List<String> plaintexts = new ArrayList<String>();
			plaintexts.add("one");
			plaintexts.add("two");

			List<VaultTransitContext> encryptionContexts = new ArrayList<VaultTransitContext>();
			encryptionContexts.add(VaultTransitContext.builder().context("oneContext".getBytes()).build());
			encryptionContexts.add(VaultTransitContext.builder().context("twoContext".getBytes()).build());

			List<VaultTransitContext> decryptionContext = new ArrayList<VaultTransitContext>();
			decryptionContext.add(VaultTransitContext.builder().context("oneContext".getBytes()).build());
			decryptionContext.add(VaultTransitContext.builder().context("wrongTwoContext".getBytes()).build());

			batchEncryptionAndDecryption(plaintexts, encryptionContexts, decryptionContext);

		} catch (VaultException e) {
			return;
		}

		Assert.fail();
	}

	private void batchEncryptionAndDecryption(List<String> plaintexts, List<VaultTransitContext> encryptionContexts,
			List<VaultTransitContext> decryptionContext) {

		List<Map<String, String>> cipherResponse = transitOperations.encrypt("mykey", plaintexts, encryptionContexts);

		List<String> cipherList = new ArrayList<String>();

		for (Map<String, String> entry : cipherResponse) {
			cipherList.add(entry.get("ciphertext"));
		}

		List<Map<String, String>> plaintextResponse = transitOperations.decrypt("mykey", cipherList, decryptionContext);

		/**
		 * check to see if decryption ended up with errors. this is special
		 * handling when context is supplied. For the other use cases, the
		 * handling should be done by the applications.
		 */
		if (decryptionContext != null) {
			for (Map<String, String> entry : plaintextResponse) {

				if (!StringUtils.isEmpty(entry.get("error"))) {
					throw new VaultException(entry.get("error"));
				}
			}
		}

		List<String> decryptedTexts = new ArrayList<String>();

		for (Map<String, String> entry : plaintextResponse) {
			decryptedTexts.add(new String(Base64Utils.decodeFromString(entry.get("plaintext"))));
		}

		Assert.assertEquals(plaintexts.size(), decryptedTexts.size());

		int i = 0;

		for (String plaintext : plaintexts) {
			Assert.assertEquals(plaintext, decryptedTexts.get(i++));
		}

	}
}
