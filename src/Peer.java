import java.util.Objects;

public class Peer {
    private String host;
    private int port;
    private PeerStatus status;
    private int peerClock;

    public Peer(String host, int port, PeerStatus status, int peerClock) {
        this.host = host;
        this.port = port;
        this.status = status;
        this.peerClock = peerClock;
    }

    public String getHost() { return host; }
    public int getPort() { return port; }
    public PeerStatus getStatus() { return status; }
    public int getPeerClock() { return peerClock; }
    
    public String getAddress() {
        return host + ":" + port;
    }

    public void setStatus(PeerStatus status) { this.status = status; }
    public void setPeerClock(int peerClock) { this.peerClock = peerClock; }

    @Override
    public String toString() {
        return getAddress() + " [" + status + ", Clock: " + peerClock + "]";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Peer peer = (Peer) o;
        return port == peer.port && Objects.equals(host, peer.host);
    }

    @Override
    public int hashCode() {
        return Objects.hash(host, port);
    }
}