import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class Node {
    private final String host;
    private final int port;
    private final CopyOnWriteArrayList<Peer> knownPeers = new CopyOnWriteArrayList<>();
    private volatile int clock = 0;
    private ServerSocket serverSocket;
    private volatile boolean running = true;
    private final File sharedDir;
    private static final int CONNECT_TIMEOUT = 2000;
    private static final int READ_TIMEOUT = 5000;

    private volatile int chunkSize = 256;
    private final Map<StatKey, StatData> statistics = new ConcurrentHashMap<>();

    public Node(String address, String neighborsFile, String sharedDirPath) {
        String[] parts = address.split(":");
        if (parts.length != 2) {
            System.err.println("Formato de endereco invalido: " + address + ". Use <host>:<porta>.");
            System.exit(1);
        }
        this.host = parts[0];
        try {
            this.port = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            System.err.println("Porta invalida: " + parts[1]);
            throw new IllegalArgumentException("Porta invalida.");
        }

        this.sharedDir = new File(sharedDirPath);
        if (!sharedDir.exists() || !sharedDir.isDirectory() || !sharedDir.canRead()) {
            System.err.println("Diretorio compartilhado invalido ou inacessivel: " + sharedDirPath);
            System.exit(1);
        }
        System.out.println("Diretorio compartilhado: " + sharedDir.getAbsolutePath());
        System.out.println("Tamanho de chunk padrao: " + this.chunkSize);

        loadPeersFromFile(neighborsFile);
    }

    private void loadPeersFromFile(String filename) {
        System.out.println("Carregando peers de: " + filename);
        try (BufferedReader br = new BufferedReader(new FileReader(filename))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split(":");
                if (parts.length != 2) {
                    System.err.println("Formato de peer invalido no arquivo: " + line);
                    continue;
                }
                String peerHost = parts[0];
                int peerPort;
                try {
                     peerPort = Integer.parseInt(parts[1]);
                } catch (NumberFormatException e) {
                    System.err.println("Porta invalida no arquivo para peer: " + line);
                    continue;
                }

                if (peerHost.equals(this.host) && peerPort == this.port) {
                    System.out.printf("Ignorando proprio endereco %s:%d do arquivo de peers.%n", peerHost, peerPort);
                    continue;
                }
                updateOrAddPeerFromFile(peerHost, peerPort, PeerStatus.OFFLINE, 0);
            }
        } catch (FileNotFoundException e) {
            System.err.println("Arquivo de peers nao encontrado: " + filename);
        } catch (IOException e) {
            System.err.println("Erro ao ler arquivo de peers: " + e.getMessage());
        }
        if (knownPeers.isEmpty()) {
            System.out.println("Nenhum peer inicial carregado do arquivo.");
        }
    }

    private synchronized void updateOrAddPeerFromFile(String host, int port, PeerStatus status, int peerClock) {
        for (Peer p : knownPeers) {
            if (p.getHost().equals(host) && p.getPort() == port) {
                System.out.printf("Peer %s:%d ja esta na lista (carregado anteriormente ou via rede), ignorando duplicata do arquivo.%n", host, port);
                return;
            }
        }
        knownPeers.add(new Peer(host, port, status, peerClock));
        System.out.printf("Adicionando novo peer %s:%d status %s, Clock: %d (do arquivo)%n", host, port, status, peerClock);
    }

    public String buildPeerListResponse(String senderAddress) {
        List<Peer> peersToSend = new ArrayList<>();
        for (Peer peer : knownPeers) {
            String peerAddress = peer.getHost() + ":" + peer.getPort();
            if (!peerAddress.equals(senderAddress) && !peerAddress.equals(getAddress())) {
                peersToSend.add(peer);
            }
        }

        StringBuilder response = new StringBuilder();
        response.append("PEER_LIST ")
               .append(peersToSend.size()).append(" ");

        for (Peer peer : peersToSend) {
            response.append(peer.getHost()).append(":")
                   .append(peer.getPort()).append(":")
                   .append(peer.getStatus()).append(":")
                   .append(peer.getPeerClock()).append(" ");
        }
        return response.toString().trim();
    }

    public void startServer() {
        try {
            serverSocket = new ServerSocket(port);
            serverSocket.setReuseAddress(true);
            System.out.printf("Servidor iniciado em %s:%d. Aguardando conexoes...%n", host, port);
            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    clientSocket.setSoTimeout(READ_TIMEOUT);
                    new Thread(new ClientHandler(clientSocket, this)).start();
                } catch (SocketException e) {
                    if (!running) {
                        System.out.println("Servidor socket fechado.");
                    } else {
                        System.err.println("Erro no accept do socket: " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            if (running) {
                System.err.printf("Erro fatal ao iniciar o servidor em %s:%d: %s%n", host, port, e.getMessage());
                running = false;
                exit();
            }
        } finally {
            if (serverSocket != null && !serverSocket.isClosed()) {
                try {
                    serverSocket.close();
                } catch (IOException e) {
                    System.err.println("Erro ao fechar server socket: " + e.getMessage());
                }
            }
            System.out.println("Thread do servidor terminada.");
        }
    }

    public synchronized int incrementClockForSend() {
        clock++;
        System.out.printf("=> Atualizando relogio para %d%n", clock);
        return clock;
    }

    public synchronized void updateClockOnReceive(int messageClock) {
        clock = Math.max(clock, messageClock) + 1;
        System.out.printf("=> Atualizando relogio para %d%n", clock);
    }

     public synchronized int getClock() {
        return clock;
    }

    public void sendHello(int index) {
        if (index < 0 || index >= knownPeers.size()) {
            System.out.println("Indice de peer invalido.");
            return;
        }
        Peer peer = knownPeers.get(index);

        int currentClock = incrementClockForSend();
        String message = String.format("%s %d HELLO", getAddress(), currentClock);

        boolean success = sendRawMessage(peer.getHost(), peer.getPort(), message);

        if (success) {
            System.out.printf("HELLO enviado para %s:%d.%n", peer.getHost(), peer.getPort());
            if (peer.getStatus() != PeerStatus.ONLINE) {
                peer.setStatus(PeerStatus.ONLINE);
                System.out.printf("Atualizando peer (send_HELLO_ok) %s:%d status ONLINE, Clock: %d%n", peer.getHost(), peer.getPort(), peer.getPeerClock());
            }
        } else {
            System.out.printf("Falha ao enviar HELLO para %s:%d.%n", peer.getHost(), peer.getPort());
            if (peer.getStatus() != PeerStatus.OFFLINE) {
                peer.setStatus(PeerStatus.OFFLINE);
                System.out.printf("Atualizando peer (send_HELLO_fail) %s:%d status OFFLINE, Clock: %d%n", peer.getHost(), peer.getPort(), peer.getPeerClock());
            }
        }
    }

    public void getPeers() {
        System.out.println("Enviando GET_PEERS para peers conhecidos...");
        if (knownPeers.isEmpty()) {
            System.out.println("Nenhum peer conhecido para consultar.");
            return;
        }
        for (Peer peer : new ArrayList<>(knownPeers)) {
            if (peer.getHost().equals(this.host) && peer.getPort() == this.port) {
                continue;
            }

            int currentClock = incrementClockForSend();
            String message = String.format("%s %d GET_PEERS", getAddress(), currentClock);
            String response = sendAndReceive(peer.getHost(), peer.getPort(), message);

            if (response != null) {
                processPeerListMessage(response);
            } else {
                System.out.printf("Falha ao comunicar com %s:%d para GET_PEERS. Marcando como OFFLINE.%n", peer.getHost(), peer.getPort());
                 if (peer.getStatus() != PeerStatus.OFFLINE) {
                    peer.setStatus(PeerStatus.OFFLINE);
                    System.out.printf("Atualizando peer (GET_PEERS_fail) %s:%d status OFFLINE, Clock: %d%n", peer.getHost(), peer.getPort(), peer.getPeerClock());
                }
            }
        }
    }

    private void processPeerListMessage(String message) {
        String[] parts = message.split(" ", 4);
        if (parts.length < 3 || !parts[2].equals("PEER_LIST")) {
            System.err.println("Resposta PEER_LIST invalida recebida: " + message);
            return;
        }

        String originFullAddress = parts[0];
        int messageClock;
        try {
            messageClock = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            System.err.println("Clock invalido na resposta PEER_LIST: " + parts[1]);
            return;
        }

        updateClockOnReceive(messageClock);

        String[] originAddrParts = originFullAddress.split(":");
        if (originAddrParts.length == 2) {
            try {
                String originHost = originAddrParts[0];
                int originPort = Integer.parseInt(originAddrParts[1]);
                updatePeerFromDirectMessage(originHost, originPort, messageClock, false);
            } catch (NumberFormatException e) {
                 System.err.println("Porta invalida no endereco de origem da PEER_LIST: " + originFullAddress);
            }
        }

        if (parts.length < 4) {
            System.out.println("PEER_LIST recebido sem corpo da lista.");
            return;
        }
        String argsString = parts[3];
        String[] listParts = argsString.split(" ", 2);
        int totalPeersInList;
        try {
            totalPeersInList = Integer.parseInt(listParts[0]);
        } catch (NumberFormatException e) {
            System.err.println("Contagem invalida em PEER_LIST: " + listParts[0]);
            return;
        }

        if (totalPeersInList == 0) return;

        if (listParts.length < 2 || listParts[1].trim().isEmpty()) {
            if (totalPeersInList > 0) System.err.println("PEER_LIST indica " + totalPeersInList + " peers, mas lista esta faltando.");
            return;
        }

        String[] peerEntries = listParts[1].trim().split(" ");
        for (String peerEntry : peerEntries) {
            String[] peerData = peerEntry.split(":");
            if (peerData.length != 4) {
                System.err.println("Formato de entrada de peer invalido em PEER_LIST: " + peerEntry);
                continue;
            }
            try {
                String host = peerData[0];
                int port = Integer.parseInt(peerData[1]);
                PeerStatus status = PeerStatus.valueOf(peerData[2].toUpperCase());
                int peerClock = Integer.parseInt(peerData[3]);
                updatePeerFromPeerList(host, port, status, peerClock);
            } catch (Exception e) {
                System.err.println("Erro ao processar entrada de PEER_LIST: " + peerEntry + " - " + e.getMessage());
            }
        }
    }


    public void listPeers() {
        System.out.println("\nLista de peers conhecidos:");
        if (knownPeers.isEmpty()) {
            System.out.println("(Nenhum peer conhecido)");
            return;
        }
        System.out.println("[0] voltar para o menu anterior");
        for (int i = 0; i < knownPeers.size(); i++) {
            Peer peer = knownPeers.get(i);
            System.out.printf("[%d] %s:%d %s (Clock: %d)%n", i + 1, peer.getHost(), peer.getPort(), peer.getStatus(), peer.getPeerClock());
        }
    }

    public void listLocalFiles() {
        System.out.println("\nArquivos locais no diretorio compartilhado (" + sharedDir.getName() + "):");
        File[] files = sharedDir.listFiles();
        if (files == null || files.length == 0) {
            System.out.println("(Nenhum arquivo encontrado)");
            return;
        }
        for (File file : files) {
            if (file.isFile()) {
                System.out.printf("- %s (%d bytes)%n", file.getName(), file.length());
            }
        }
    }

    public void exit() {
        System.out.println("Iniciando processo de saida...");
        running = false;

        System.out.println("Enviando BYE para peers ONLINE...");
        int currentClock = incrementClockForSend();
        String byeMessage = String.format("%s %d BYE", getAddress(), currentClock);

        for (Peer peer : knownPeers) {
            if (peer.getStatus() == PeerStatus.ONLINE) {
                sendRawMessage(peer.getHost(), peer.getPort(), byeMessage);
            }
        }

        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                System.out.println("Fechando socket do servidor...");
                serverSocket.close();
            } catch (IOException e) {
                System.err.println("Erro ao fechar server socket durante saida: " + e.getMessage());
            }
        }
        System.out.println("Procedimento de saida concluido.");
    }

    public synchronized void updatePeerFromDirectMessage(String host, int port, int messageClockFromOrigin, boolean isByeMessage) {
        if (host.equals(this.host) && port == this.port) return;

        for (Peer peer : knownPeers) {
            if (peer.getHost().equals(host) && peer.getPort() == port) {
                PeerStatus newStatus = isByeMessage ? PeerStatus.OFFLINE : PeerStatus.ONLINE;
                if (messageClockFromOrigin > peer.getPeerClock()) {
                    peer.setPeerClock(messageClockFromOrigin);
                    peer.setStatus(newStatus);
                    System.out.printf("Atualizando peer (direct) %s:%d status %s, Clock: %d%n", host, port, peer.getStatus(), peer.getPeerClock());
                } else if (peer.getStatus() != newStatus && isByeMessage) {
                    peer.setStatus(newStatus);
                    System.out.printf("Atualizando peer (direct-BYE) %s:%d status %s, Clock: %d%n", host, port, peer.getStatus(), peer.getPeerClock());
                }
                return;
            }
        }
        PeerStatus initialStatus = isByeMessage ? PeerStatus.OFFLINE : PeerStatus.ONLINE;
        Peer newPeer = new Peer(host, port, initialStatus, messageClockFromOrigin);
        knownPeers.add(newPeer);
        System.out.printf("Adicionando novo peer (direct) %s:%d status %s, Clock: %d%n", host, port, newPeer.getStatus(), newPeer.getPeerClock());
    }

    public synchronized void updatePeerFromPeerList(String listedHost, int listedPort, PeerStatus listedStatus, int listedPeerClock) {
        if (listedHost.equals(this.host) && listedPort == this.port) return;

        for (Peer peer : knownPeers) {
            if (peer.getHost().equals(listedHost) && peer.getPort() == listedPort) {
                if (listedPeerClock > peer.getPeerClock()) {
                    peer.setPeerClock(listedPeerClock);
                    peer.setStatus(listedStatus);
                    System.out.printf("Atualizando peer (PEER_LIST) %s:%d status %s, Clock: %d%n", listedHost, listedPort, peer.getStatus(), peer.getPeerClock());
                }
                return;
            }
        }
        Peer newPeer = new Peer(listedHost, listedPort, listedStatus, listedPeerClock);
        knownPeers.add(newPeer);
        System.out.printf("Adicionando novo peer (PEER_LIST) %s:%d status %s, Clock: %d%n", listedHost, listedPort, newPeer.getStatus(), newPeer.getPeerClock());
    }

    private String sendAndReceive(String targetHost, int targetPort, String message) {
        System.out.printf("Encaminhando mensagem \"%s\" para %s:%d%n", message.trim(), targetHost, targetPort);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(targetHost, targetPort), CONNECT_TIMEOUT);
            socket.setSoTimeout(READ_TIMEOUT);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out.println(message);
            String response = in.readLine();
            if (response != null) {
                 if (response.contains("FILE") && response.length() > 200) {
                     System.out.printf("Resposta recebida de %s:%d: \"%s...\"%n", targetHost, targetPort, response.substring(0, 200));
                 } else {
                     System.out.printf("Resposta recebida de %s:%d: \"%s\"%n", targetHost, targetPort, response.trim());
                 }
            } else {
                 System.out.printf("Nenhuma resposta recebida de %s:%d (timeout ou conexao fechada)%n", targetHost, targetPort);
            }
            return response;
        } catch (SocketTimeoutException e) {
            System.err.printf("Timeout ao comunicar com %s:%d%n", targetHost, targetPort);
            return null;
        } catch (IOException e) {
            System.err.printf("Erro de I/O ao comunicar com %s:%d: %s%n", targetHost, targetPort, e.getMessage());
            return null;
        }
    }

    private boolean sendRawMessage(String targetHost, int targetPort, String message) {
        System.out.printf("Encaminhando mensagem \"%s\" para %s:%d%n", message.trim(), targetHost, targetPort);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(targetHost, targetPort), CONNECT_TIMEOUT);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            out.println(message);
            out.flush();
            return true;
        } catch (SocketTimeoutException e) {
            System.err.printf("Timeout ao conectar com %s:%d%n", targetHost, targetPort);
            return false;
        } catch (IOException e) {
            System.err.printf("Erro de I/O ao enviar para %s:%d: %s%n", targetHost, targetPort, e.getMessage());
            return false;
        }
    }

    public void searchAndDownloadFiles(Scanner scanner) {
        System.out.println("Buscando arquivos na rede...");
        Map<String, DiscoveredFileGroup> discoveredFileGroups = new HashMap<>();

        List<Peer> onlinePeers = knownPeers.stream()
                .filter(p -> p.getStatus() == PeerStatus.ONLINE && !getAddress().equals(p.getHost() + ":" + p.getPort()))
                .collect(Collectors.toList());

        if (onlinePeers.isEmpty()) {
            System.out.println("Nenhum peer ONLINE conhecido para buscar arquivos.");
            return;
        }

        for (Peer peer : onlinePeers) {
            int localClock = incrementClockForSend();
            String lsMessage = String.format("%s %d LS", getAddress(), localClock);
            String response = sendAndReceive(peer.getHost(), peer.getPort(), lsMessage);

            if (response != null) {
                processLsListResponse(response, peer, discoveredFileGroups);
            } else {
                if (peer.getStatus() != PeerStatus.OFFLINE) {
                    peer.setStatus(PeerStatus.OFFLINE);
                    System.out.printf("Atualizando peer (LS_send_fail) %s:%d status OFFLINE, Clock: %d%n", peer.getHost(), peer.getPort(), peer.getPeerClock());
                }
            }
        }

        if (discoveredFileGroups.isEmpty()) {
            System.out.println("Nenhum arquivo encontrado na rede.");
            return;
        }

        List<DiscoveredFileGroup> displayList = new ArrayList<>(discoveredFileGroups.values());
        displayGroupedFiles(displayList);

        System.out.print("Digite o numero do arquivo para fazer o download: ");
        int choice = -1;
        try {
            if (scanner.hasNextInt()) {
                choice = scanner.nextInt();
            }
        } catch (InputMismatchException ignored) {
        } finally {
            scanner.nextLine();
        }

        if (choice <= 0 || choice > displayList.size()) {
            System.out.println("Selecao invalida ou cancelada.");
            return;
        }

        DiscoveredFileGroup chosenGroup = displayList.get(choice - 1);
        System.out.printf("arquivo escolhido %s de %s%n", chosenGroup.fileName,
                chosenGroup.peerLocations.stream().map(loc -> loc.peerHost + ":" + loc.peerPort).collect(Collectors.joining(", ")));

        downloadFileInChunks(chosenGroup);
    }

    private void processLsListResponse(String response, Peer peer, Map<String, DiscoveredFileGroup> fileGroups) {
        String[] parts = response.split(" ", 4);
        if (parts.length >= 4 && parts[2].equals("LS_LIST")) {
            try {
                int responseClock = Integer.parseInt(parts[1]);
                updateClockOnReceive(responseClock);
                updatePeerFromDirectMessage(peer.getHost(), peer.getPort(), responseClock, false);

                int count = Integer.parseInt(parts[3].split(" ")[0]);
                if (count > 0 && parts[3].split(" ").length > 1) {
                    String[] fileEntries = parts[3].substring(parts[3].indexOf(" ") + 1).trim().split(" ");
                    for (String entry : fileEntries) {
                        String[] fileInfo = entry.split(":");
                        if (fileInfo.length == 2) {
                            String fileName = fileInfo[0];
                            long fileSize = Long.parseLong(fileInfo[1]);
                            String fileKey = fileName + ":" + fileSize; // Group by name and size
                            DiscoveredFileGroup group = fileGroups.computeIfAbsent(fileKey, k -> new DiscoveredFileGroup(fileName, fileSize));
                            group.addPeerLocation(peer.getHost(), peer.getPort());
                        }
                    }
                }
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                System.err.println("Formato invalido de LS_LIST recebido de " + peer.getAddress() + ": " + response + " Erro: " + e.getMessage());
            }
        } else {
            System.err.println("Resposta inesperada para LS de " + peer.getAddress() + ": " + response);
            if (peer.getStatus() != PeerStatus.OFFLINE) {
                peer.setStatus(PeerStatus.OFFLINE);
                System.out.printf("Atualizando peer (LS_response_invalid) %s:%d status OFFLINE, Clock: %d%n", peer.getHost(), peer.getPort(), peer.getPeerClock());
            }
        }
    }

    private void displayGroupedFiles(List<DiscoveredFileGroup> displayList) {
        System.out.println("\nArquivos encontrados na rede:");
        System.out.println("[0] <Cancelar>");
        for (int i = 0; i < displayList.size(); i++) {
            DiscoveredFileGroup group = displayList.get(i);
            String peers = group.peerLocations.stream()
                    .map(loc -> loc.peerHost + ":" + loc.peerPort)
                    .collect(Collectors.joining(", "));
            System.out.printf("[%d] %s | %d bytes | Peers: %s%n", i + 1, group.fileName, group.fileSize, peers);
        }
    }

    private void downloadFileInChunks(DiscoveredFileGroup chosenGroup) {
        long fileSize = chosenGroup.fileSize;
        if (fileSize == 0) {
            try {
                Files.write(Paths.get(sharedDir.getAbsolutePath(), chosenGroup.fileName), new byte[0], StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                System.out.printf("Download do arquivo (vazio) %s finalizado.%n", chosenGroup.fileName);
            } catch (IOException e) {
                System.err.println("Erro ao salvar arquivo vazio " + chosenGroup.fileName + ": " + e.getMessage());
            }
            return;
        }

        int numChunks = (int) Math.ceil((double) fileSize / this.chunkSize);
        Map<Integer, byte[]> receivedChunks = new ConcurrentHashMap<>();
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(chosenGroup.peerLocations.size(), 10));
        long startTime = System.nanoTime();

        for (int i = 0; i < numChunks; i++) {
            final int chunkIndex = i;
            FileLocation location = chosenGroup.peerLocations.get(i % chosenGroup.peerLocations.size()); // Round-robin

            executor.submit(() -> {
                int localClock = incrementClockForSend();
                String dlMessage = String.format("%s %d DL %s %d %d", getAddress(), localClock, chosenGroup.fileName, this.chunkSize, chunkIndex);
                String fileResponse = sendAndReceive(location.peerHost, location.peerPort, dlMessage);

                if (fileResponse != null) {
                    String[] parts = fileResponse.split(" ", 7);
                    if (parts.length == 7 && parts[2].equals("FILE") && parts[3].equals(chosenGroup.fileName)) {
                        try {
                            int responseChunkIndex = Integer.parseInt(parts[5]);
                            if (responseChunkIndex == chunkIndex) {
                                receivedChunks.put(chunkIndex, Base64.getDecoder().decode(parts[6]));
                            }
                        } catch (Exception e) {
                            System.err.println("Erro ao processar chunk " + chunkIndex + ": " + e.getMessage());
                        }
                    }
                } else {
                    System.err.println("Falha ao baixar chunk " + chunkIndex + " de " + location.peerHost + ":" + location.peerPort);
                }
            });
        }

        executor.shutdown();
        try {
            executor.awaitTermination(5, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Download interrompido.");
            return;
        }

        long endTime = System.nanoTime();
        double durationSeconds = (endTime - startTime) / 1_000_000_000.0;
        
        if (receivedChunks.size() == numChunks) {
            Path finalPath = Paths.get(sharedDir.getAbsolutePath(), chosenGroup.fileName);
            try (FileOutputStream fos = new FileOutputStream(finalPath.toFile())) {
                for (int i = 0; i < numChunks; i++) {
                    fos.write(receivedChunks.get(i));
                }
                System.out.printf("Download do arquivo %s finalizado em %.4f segundos.%n", chosenGroup.fileName, durationSeconds);
                StatKey key = new StatKey(this.chunkSize, chosenGroup.peerLocations.size(), fileSize);
                statistics.computeIfAbsent(key, k -> new StatData()).addTiming(durationSeconds);
            } catch (IOException e) {
                System.err.println("Erro ao montar e salvar o arquivo " + chosenGroup.fileName + ": " + e.getMessage());
            }
        } else {
            System.err.println("Download falhou. Nem todos os chunks foram recebidos. Esperado: " + numChunks + ", Recebido: " + receivedChunks.size());
        }
    }

    public String getAddress() {
        return host + ":" + port;
    }

    public boolean isRunning() {
        return running;
    }

    public void setChunkSize(int newSize) {
        if (newSize > 0) {
            this.chunkSize = newSize;
            System.out.println("Tamanho de chunk alterado: " + this.chunkSize);
        } else {
            System.out.println("Tamanho de chunk invalido. Deve ser maior que 0.");
        }
    }

    public void displayStatistics() {
        System.out.println("\n--- Estatisticas de Download ---");
        if (statistics.isEmpty()) {
            System.out.println("(Nenhuma estatistica coletada ainda)");
            return;
        }

        System.out.format("%-12s | %-8s | %-12s | %-2s | %-12s | %-12s%n",
                "Tam. chunk", "N peers", "Tam. arquivo", "N", "Tempo (s)", "Desvio Padrao");
        System.out.println(new String(new char[78]).replace("\0", "-"));

        statistics.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<StatKey, StatData> e) -> e.getKey().chunkSize)
                        .thenComparing(e -> e.getKey().numPeers)
                        .thenComparing(e -> e.getKey().fileSize))
                .forEach(entry -> {
                    StatKey key = entry.getKey();
                    StatData data = entry.getValue();
                    System.out.format("%-12d | %-8d | %-12d | %-2d | %-12.6f | %-12.6f%n",
                            key.chunkSize, key.numPeers, key.fileSize,
                            data.getSampleSize(), data.getMean(), data.getStdDev());
                });
    }

    private static class FileLocation {
        String peerHost;
        int peerPort;
        FileLocation(String peerHost, int peerPort) {
            this.peerHost = peerHost;
            this.peerPort = peerPort;
        }
    }

    private static class DiscoveredFileGroup {
        String fileName;
        long fileSize;
        List<FileLocation> peerLocations = new ArrayList<>();

        DiscoveredFileGroup(String fileName, long fileSize) {
            this.fileName = fileName;
            this.fileSize = fileSize;
        }

        void addPeerLocation(String host, int port) {
            this.peerLocations.add(new FileLocation(host, port));
        }
    }

    private static class StatKey {
        final int chunkSize;
        final int numPeers;
        final long fileSize;

        StatKey(int chunkSize, int numPeers, long fileSize) {
            this.chunkSize = chunkSize;
            this.numPeers = numPeers;
            this.fileSize = fileSize;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            StatKey statKey = (StatKey) o;
            return chunkSize == statKey.chunkSize && numPeers == statKey.numPeers && fileSize == statKey.fileSize;
        }

        @Override
        public int hashCode() {
            return Objects.hash(chunkSize, numPeers, fileSize);
        }
    }

    private static class StatData {
        private final List<Double> timings = new CopyOnWriteArrayList<>();

        void addTiming(double time) {
            timings.add(time);
        }

        int getSampleSize() {
            return timings.size();
        }

        double getMean() {
            if (timings.isEmpty()) return 0.0;
            return timings.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        }

        double getStdDev() {
            int n = timings.size();
            if (n < 2) return 0.0;
            double mean = getMean();
            double variance = timings.stream()
                    .mapToDouble(Double::doubleValue)
                    .map(d -> (d - mean) * (d - mean))
                    .sum() / n;
            return Math.sqrt(variance);
        }
    }
}