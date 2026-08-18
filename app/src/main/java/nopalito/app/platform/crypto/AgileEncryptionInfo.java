/*
 *
 * Copyright 2025-2026 The FairScan authors
 * Copyright 2026 Ruben Matias
 *
 * Modified by Ruben Matias in 2026.
 * This file is part of the Nopalito Scan fork.
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 *
 */

package nopalito.app.platform.crypto;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Agile encryption descriptor (stream "EncryptionInfo"): 8-byte header
 * (major=4, minor=4, flags=0x40) + UTF-8 XML without BOM (same as the reference).
 * Per MS-OFFCRYPTO "Agile Encryption"; the 8-byte blockKeys are constants of the
 * keyEncryptor and of the integrity (dataIntegrity1/2).
 */
final class AgileEncryptionInfo {
    static final int SPIN_COUNT = 100000;
    static final int SALT_SIZE = 16;
    static final int BLOCK_SIZE = 16;
    static final int KEY_BITS = 256;
    static final int HASH_SIZE = 64;

    // Fixed keyEncryptor block keys (MS-OFFCRYPTO / msoffcrypto blkKey_*).
    static final byte[] BLOCK_KEY_VERIFIER_HASH_INPUT = {
            (byte) 0xFE, (byte) 0xA7, (byte) 0xD2, 0x76, 0x3B, 0x4B, (byte) 0x9E, 0x79};
    static final byte[] BLOCK_KEY_VERIFIER_HASH_VALUE = {
            (byte) 0xD7, (byte) 0xAA, 0x0F, 0x6D, 0x30, 0x61, 0x34, 0x4E};
    static final byte[] BLOCK_KEY_ENCRYPTED_KEY_VALUE = {
            0x14, 0x6E, 0x0B, (byte) 0xE7, (byte) 0xAB, (byte) 0xAC, (byte) 0xD0, (byte) 0xD6};
    static final byte[] BLOCK_KEY_DATA_INTEGRITY_1 = {
            0x5F, (byte) 0xB2, (byte) 0xAD, 0x01, 0x0C, (byte) 0xB9, (byte) 0xE1, (byte) 0xF6};
    static final byte[] BLOCK_KEY_DATA_INTEGRITY_2 = {
            (byte) 0xA0, 0x67, 0x7F, 0x02, (byte) 0xB2, 0x2C, (byte) 0x84, 0x33};

    static final boolean DESCRIPTOR_BOM = false;

    static final String NS = "http://schemas.microsoft.com/office/2006/encryption";
    static final String NS_P = "http://schemas.microsoft.com/office/2006/keyEncryptor/password";
    static final String NS_C = "http://schemas.microsoft.com/office/2006/keyEncryptor/certificate";

    byte[] passwordSalt;
    byte[] keyDataSalt;
    int spinCount = SPIN_COUNT;
    byte[] encryptedVerifierHashInput;
    byte[] encryptedVerifierHashValue;
    byte[] encryptedKeyValue;
    byte[] encryptedHmacKey;
    byte[] encryptedHmacValue;

    private static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    /**
     * Parses the EncryptionInfo stream (8-byte header + XML) for decryption.
     * The XML parser detects the descriptor encoding automatically (UTF-16LE BOM in
     * real Word files, UTF-8 in the ones we generate).
     */
    static AgileEncryptionInfo parse(byte[] stream) {
        if (stream.length < 8) throw new CryptoException("EncryptionInfo too short");
        int major = CryptoUtil.readLe16(stream, 0);
        int minor = CryptoUtil.readLe16(stream, 2);
        if (major != 4 || minor != 4) {
            throw new CryptoException("Not agile encryption (v" + major + "." + minor + ")");
        }
        byte[] xmlBytes = CryptoUtil.slice(stream, 8, stream.length - 8);
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true); // getLocalName() returns the name without prefix
            Document doc = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xmlBytes));
            Element keyData = firstElement(doc, "keyData");
            Element integrity = firstElement(doc, "dataIntegrity");
            Element encryptedKey = firstElement(doc, "encryptedKey");

            AgileEncryptionInfo info = new AgileEncryptionInfo();
            info.keyDataSalt = Base64.getDecoder().decode(keyData.getAttribute("saltValue"));
            info.encryptedHmacKey = Base64.getDecoder().decode(integrity.getAttribute("encryptedHmacKey"));
            info.encryptedHmacValue = Base64.getDecoder().decode(integrity.getAttribute("encryptedHmacValue"));
            info.passwordSalt = Base64.getDecoder().decode(encryptedKey.getAttribute("saltValue"));
            info.spinCount = Integer.parseInt(encryptedKey.getAttribute("spinCount"));
            info.encryptedVerifierHashInput =
                    Base64.getDecoder().decode(encryptedKey.getAttribute("encryptedVerifierHashInput"));
            info.encryptedVerifierHashValue =
                    Base64.getDecoder().decode(encryptedKey.getAttribute("encryptedVerifierHashValue"));
            info.encryptedKeyValue = Base64.getDecoder().decode(encryptedKey.getAttribute("encryptedKeyValue"));
            return info;
        } catch (CryptoException e) {
            throw e;
        } catch (Exception e) {
            throw new CryptoException("Cannot parse EncryptionInfo XML", e);
        }
    }

    private static Element firstElement(Document doc, String localName) {
        org.w3c.dom.NodeList list = doc.getElementsByTagName("*");
        for (int i = 0; i < list.getLength(); i++) {
            Node node = list.item(i);
            if (node instanceof Element) {
                Element el = (Element) node;
                String ln = el.getLocalName();
                if (ln != null && ln.equals(localName)) return el;
            }
        }
        throw new CryptoException("Element <" + localName + "> not found in descriptor");
    }

    /**
     * 8-byte header (major=4, minor=4, flags=0x40) + descriptor XML.
     */
    byte[] toEncryptionInfoBytes() {
        byte[] header = CryptoUtil.concat(
                CryptoUtil.le16(), CryptoUtil.le16(), CryptoUtil.le32(0x40));
        return CryptoUtil.concat(header, xmlBytes());
    }

    private byte[] xmlBytes() {
        byte[] bytes = toXml().getBytes(StandardCharsets.UTF_8);
        if (DESCRIPTOR_BOM) {
            return CryptoUtil.concat(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, bytes);
        }
        return bytes;
    }

    String toXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                "<encryption xmlns=\"" + NS + "\"" +
                " xmlns:p=\"" + NS_P + "\"" +
                " xmlns:c=\"" + NS_C + "\">\n" +
                "    <keyData saltSize=\"" + SALT_SIZE +
                "\" blockSize=\"" + BLOCK_SIZE +
                "\" keyBits=\"" + KEY_BITS +
                "\" hashSize=\"" + HASH_SIZE +
                "\" cipherAlgorithm=\"AES\" cipherChaining=\"ChainingModeCBC\" hashAlgorithm=\"SHA512\"" +
                " saltValue=\"" + b64(keyDataSalt) + "\" />\n" +
                "    <dataIntegrity encryptedHmacKey=\"" + b64(encryptedHmacKey) +
                "\" encryptedHmacValue=\"" + b64(encryptedHmacValue) + "\" />\n" +
                "    <keyEncryptors>\n" +
                "        <keyEncryptor uri=\"" + NS_P + "\">\n" +
                "            <p:encryptedKey spinCount=\"" + spinCount +
                "\" saltSize=\"" + SALT_SIZE +
                "\" blockSize=\"" + BLOCK_SIZE +
                "\" keyBits=\"" + KEY_BITS +
                "\" hashSize=\"" + HASH_SIZE +
                "\" cipherAlgorithm=\"AES\" cipherChaining=\"ChainingModeCBC\" hashAlgorithm=\"SHA512\"" +
                " saltValue=\"" + b64(passwordSalt) +
                "\" encryptedVerifierHashInput=\"" + b64(encryptedVerifierHashInput) +
                "\" encryptedVerifierHashValue=\"" + b64(encryptedVerifierHashValue) +
                "\" encryptedKeyValue=\"" + b64(encryptedKeyValue) + "\" />\n" +
                "        </keyEncryptor>\n" +
                "    </keyEncryptors>\n" +
                "</encryption>\n";
    }
}