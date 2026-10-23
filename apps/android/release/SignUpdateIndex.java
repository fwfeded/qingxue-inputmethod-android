//! 用 JDK 标准库生成独立更新签名密钥或签署 JSON 负载；私钥不得提交。
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.util.Base64;

class SignUpdateIndex {
    public static void main(String[] args) throws Exception {
        if (args.length != 4 && args.length != 3) throw new IllegalArgumentException("keygen private.pk8 public.txt | sign private.pk8 payload.json index.json");
        if (args[0].equals("keygen") && args.length == 3) {
            Path privatePath = Path.of(args[1]);
            Path publicPath = Path.of(args[2]);
            if (Files.exists(privatePath) || Files.exists(publicPath)) throw new IllegalStateException("Refuse to overwrite existing key files");
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair pair = generator.generateKeyPair();
            Files.createDirectories(privatePath.toAbsolutePath().getParent());
            Files.createDirectories(publicPath.toAbsolutePath().getParent());
            Files.write(privatePath, pair.getPrivate().getEncoded(), StandardOpenOption.CREATE_NEW);
            Files.writeString(publicPath, Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            System.out.println("Created update signing key; keep private.pk8 outside public source and back it up securely.");
        } else if (args[0].equals("sign") && args.length == 4) {
            byte[] payload = Files.readAllBytes(Path.of(args[2]));
            if (payload.length > 180000) throw new IllegalArgumentException("Payload too large");
            PrivateKey key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(Path.of(args[1]))));
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(key); signer.update(payload);
            String envelope = "{\"payload\":\"" + Base64.getEncoder().encodeToString(payload) + "\",\"signature\":\"" + Base64.getEncoder().encodeToString(signer.sign()) + "\"}\n";
            Files.writeString(Path.of(args[3]), envelope, StandardCharsets.UTF_8);
            System.out.println("Signed index. No network or publication performed.");
        } else throw new IllegalArgumentException("Invalid command");
    }
}
