package com.mipago.ex2center.utils

import android.util.Base64
import android.util.Log
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Verifies the signed app catalog (`apps.json` + `apps.json.sig`).
 *
 * The catalog decides what EX2 Store installs with system privileges, so a remote catalog is
 * trusted only if it carries a valid ECDSA P-256 / SHA-256 signature made with the project's
 * catalog key (kept offline in `signing/catalog-signing-key.pem`, never in the repository).
 * Signing: `scripts/update-catalog.py` (openssl dgst -sha256 -sign …, base64 DER signature).
 */
object CatalogVerifier {

    private const val TAG = "CatalogVerifier"

    /** X.509 SubjectPublicKeyInfo (DER, base64) of the catalog signing key. */
    private const val PUBLIC_KEY_B64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAECATu8pyiRmmasRDq8DmFtrmiDR+FezIE5zihFxgYBSNEQnuTKHllz0kJlHkLrbiIZQHGmmCOq7ee3Mj49WjRaw=="

    fun verify(data: ByteArray, signatureB64: String?): Boolean {
        if (signatureB64.isNullOrBlank()) return false
        return try {
            val key = KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(Base64.decode(PUBLIC_KEY_B64, Base64.DEFAULT)))
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(data)
                verify(Base64.decode(signatureB64.trim(), Base64.DEFAULT))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Signature check failed: ${t.message}")
            false
        }
    }
}
