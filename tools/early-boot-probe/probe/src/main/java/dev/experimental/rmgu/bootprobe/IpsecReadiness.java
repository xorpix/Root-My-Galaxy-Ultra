package dev.experimental.rmgu.bootprobe;

import android.content.Context;
import android.net.IpSecAlgorithm;
import android.net.IpSecManager;
import android.net.IpSecTransform;
import android.os.SystemClock;
import org.json.JSONObject;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.security.SecureRandom;

/** Normal IPsec API exercise, matching DirtyFrag's prerequisites without any exploit stage. */
final class IpsecReadiness {
    static JSONObject check(Context context) throws Exception {
        long started = SystemClock.elapsedRealtime();
        JSONObject result = new JSONObject().put("ready", false).put("started_ms", started);
        IpSecManager.UdpEncapsulationSocket socket = null;
        IpSecManager.SecurityParameterIndex spi = null;
        IpSecTransform transform = null;
        try {
            IpSecManager manager = context.getSystemService(IpSecManager.class);
            result.put("service_present", manager != null);
            if (manager == null) return result;
            socket = manager.openUdpEncapsulationSocket();
            result.put("udp_socket_ready", true);
            InetAddress loopback = InetAddress.getByName("127.0.0.1");
            spi = manager.allocateSecurityParameterIndex(loopback);
            result.put("spi_ready", true);
            byte[] aes = new byte[32];
            byte[] hmac = new byte[32];
            SecureRandom random = new SecureRandom();
            random.nextBytes(aes);
            random.nextBytes(hmac);
            int senderPort;
            try (DatagramSocket sender = new DatagramSocket()) { senderPort = sender.getLocalPort(); }
            transform = new IpSecTransform.Builder(context)
                    .setEncryption(new IpSecAlgorithm(IpSecAlgorithm.CRYPT_AES_CBC, aes))
                    .setAuthentication(new IpSecAlgorithm(IpSecAlgorithm.AUTH_HMAC_SHA256, hmac, 128))
                    .setIpv4Encapsulation(socket, senderPort)
                    .buildTransportModeTransform(loopback, spi);
            result.put("transform_ready", true).put("ready", true)
                    .put("ready_ms", SystemClock.elapsedRealtime())
                    .put("snapshot_when_ready", ProbeRuntime.snapshot(context));
        } catch (Exception error) {
            result.put("error_class", error.getClass().getSimpleName())
                    .put("error", String.valueOf(error.getMessage()));
        } finally {
            // The test never applies a transform to a network socket; release every allocation.
            if (transform != null) try { transform.close(); } catch (RuntimeException e) { result.put("transform_cleanup_error", e.getClass().getSimpleName()); }
            if (spi != null) try { spi.close(); } catch (RuntimeException e) { result.put("spi_cleanup_error", e.getClass().getSimpleName()); }
            if (socket != null) try { socket.close(); } catch (Exception e) { result.put("socket_cleanup_error", e.getClass().getSimpleName()); }
            result.put("finished_ms", SystemClock.elapsedRealtime()).put("duration_ms", SystemClock.elapsedRealtime() - started);
        }
        return result;
    }
}
