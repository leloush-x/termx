package app.termx.runtime

import android.content.Context
import android.os.Build
import android.util.Base64
import app.termx.BuildConfig
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest

data class RootfsPackage(
    val distroId: String,
    val distroName: String,
    val version: String,
    val abi: String,
    val url: String,
    val size: Long,
    val sha256: String,
)

/** Verifies the detached OpenSSH Ed25519 signature before parsing any JSON fields. */
object DistroManifest {
    private const val SIGNATURE_NAMESPACE = "termx-distro-manifest"
    private val supportedAbis = setOf("arm64-v8a", "x86_64")

    fun load(context: Context): List<RootfsPackage> {
        val bytes = context.assets.open("distro-manifest.json").use { it.readBytes() }
        val armor = context.assets.open("distro-manifest.sig").bufferedReader().use { it.readText() }
        val publicKey = context.assets.open("distro-manifest.pub.b64").bufferedReader().use {
            Base64.decode(it.readText().trim(), Base64.NO_WRAP)
        }
        require(verifyOpenSshSignature(bytes, armor, publicKey)) {
            "Distro manifest signature is invalid"
        }

        val root = JSONObject(String(bytes, Charsets.UTF_8))
        require(root.getInt("schema") == 1) { "Unsupported distro manifest schema" }
        val abi = Build.SUPPORTED_ABIS.firstOrNull(supportedAbis::contains)
            ?: throw IllegalStateException("This device ABI is not supported")
        val packages = mutableListOf<RootfsPackage>()
        val distros = root.getJSONArray("distros")
        for (index in 0 until distros.length()) {
            val distro = distros.getJSONObject(index)
            val id = distro.getString("id")
            require(id.matches(Regex("[a-z][a-z0-9_-]{0,31}"))) { "Invalid distro id" }
            val arch = distro.getJSONObject("architectures").optJSONObject(abi) ?: continue
            val url = arch.getString("url")
            require(url.startsWith("https://")) { "Distro URLs must use HTTPS" }
            val size = arch.getLong("size")
            require(size > 0) { "Distro size must be positive" }
            val sha256 = arch.getString("sha256")
            require(sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "Invalid SHA-256 digest" }
            require(arch.getInt("minAppVersion") <= BuildConfig.VERSION_CODE) {
                "Distro requires a newer TermX version"
            }
            packages += RootfsPackage(
                distroId = id,
                distroName = distro.getString("name"),
                version = distro.getString("version"),
                abi = abi,
                url = url,
                size = size,
                sha256 = sha256.lowercase(),
            )
        }
        require(packages.any { it.distroId == "wolfi" }) { "Wolfi is missing from the manifest" }
        return packages
    }

    private fun verifyOpenSshSignature(message: ByteArray, armor: String, rawPublicKey: ByteArray): Boolean {
        val encoded = armor.lineSequence()
            .filterNot { it.startsWith("-----") }
            .joinToString("")
        val envelope = Base64.decode(encoded, Base64.DEFAULT)
        val input = DataInputStream(ByteArrayInputStream(envelope))
        val magic = ByteArray(6).also(input::readFully)
        require(String(magic, Charsets.US_ASCII) == "SSHSIG") { "Invalid signature envelope" }
        require(input.readInt() == 1) { "Unsupported signature envelope version" }
        val envelopeKey = DataInputStream(ByteArrayInputStream(input.readSshString()))
        require(String(envelopeKey.readSshString(), Charsets.US_ASCII) == "ssh-ed25519") {
            "Unsupported envelope key"
        }
        require(envelopeKey.readSshString().contentEquals(rawPublicKey) && envelopeKey.available() == 0) {
            "Signature key does not match the embedded key"
        }
        val namespace = input.readSshString()
        val reserved = input.readSshString()
        val hashName = input.readSshString()
        val signatureBlob = input.readSshString()
        require(input.available() == 0) { "Trailing signature data" }
        require(String(namespace, Charsets.UTF_8) == SIGNATURE_NAMESPACE) { "Wrong signature namespace" }
        require(reserved.isEmpty()) { "Unsupported signature options" }

        val digestName = when (String(hashName, Charsets.US_ASCII)) {
            "sha256" -> "SHA-256"
            "sha512" -> "SHA-512"
            else -> throw IllegalArgumentException("Unsupported signature digest")
        }
        val signatureInput = DataInputStream(ByteArrayInputStream(signatureBlob))
        require(String(signatureInput.readSshString(), Charsets.US_ASCII) == "ssh-ed25519") {
            "Unsupported signature algorithm"
        }
        val signature = signatureInput.readSshString()
        require(signature.size == 64 && signatureInput.available() == 0) { "Invalid Ed25519 signature" }

        val signedMessage = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { data ->
                data.write(magic)
                data.writeSshString(namespace)
                data.writeSshString(reserved)
                data.writeSshString(hashName)
                data.writeSshString(MessageDigest.getInstance(digestName).digest(message))
            }
        }.toByteArray()

        val key = EdDSAPublicKey(
            EdDSAPublicKeySpec(rawPublicKey, EdDSANamedCurveTable.getByName("Ed25519")),
        )
        return EdDSAEngine().run {
            initVerify(key)
            update(signedMessage)
            verify(signature)
        }
    }

    private fun DataInputStream.readSshString(): ByteArray {
        val length = readInt()
        if (length < 0 || length > 64 * 1024 || length > available()) {
            throw IOException("Invalid SSH signature field length")
        }
        return ByteArray(length).also(::readFully)
    }

    private fun DataOutputStream.writeSshString(value: ByteArray) {
        writeInt(value.size)
        write(value)
    }
}
