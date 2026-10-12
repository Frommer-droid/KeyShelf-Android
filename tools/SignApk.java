import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;

public class SignApk {
    public static void main(String[] args) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var input = new FileInputStream(args[0])) { store.load(input, "android".toCharArray()); }
        var key = (PrivateKey) store.getKey("keyshelf", "android".toCharArray());
        var cert = (X509Certificate) store.getCertificate("keyshelf");
        var signer = new ApkSigner.SignerConfig.Builder("KeyShelf", key, List.of(cert)).build();
        new ApkSigner.Builder(List.of(signer)).setInputApk(new File(args[1])).setOutputApk(new File(args[2]))
            .setMinSdkVersion(26).setV1SigningEnabled(true).setV2SigningEnabled(true).setV3SigningEnabled(true).build().sign();
        var result = new ApkVerifier.Builder(new File(args[2])).build().verify();
        if (!result.isVerified()) throw new IllegalStateException("APK signature validation failed: " + result.getErrors());
        System.out.println("APK signature verified (v2=" + result.isVerifiedUsingV2Scheme() + ", v3=" + result.isVerifiedUsingV3Scheme() + ")");
    }
}
