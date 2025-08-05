import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;

public class ClientHandler implements Runnable {
    private final Socket socket;
    private final Node node;

    public ClientHandler(Socket socket, Node node) {
        this.socket = socket;
        this.node = node;
    }

    @Override
    public void run() {
        try (
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        ) {
            String message = in.readLine();
            if (message != null) {
                processMessage(message.trim());
            } else {
                 System.out.printf("Conexao fechada por %s sem enviar mensagem.%n", socket.getRemoteSocketAddress());
            }
        } catch (SocketTimeoutException e) {
             System.err.printf("Timeout ao ler dados de %s: %s%n", socket.getRemoteSocketAddress(), e.getMessage());
        } catch (IOException e) {
            if (node.isRunning()) {
                 System.err.printf("Erro de I/O no ClientHandler para %s: %s%n", socket.getRemoteSocketAddress(), e.getMessage());
            }
        } catch (Exception e) {
             System.err.printf("Erro inesperado no ClientHandler para %s: %s%n", socket.getRemoteSocketAddress(), e.getMessage());
             e.printStackTrace();
        } finally {
            if (socket != null && !socket.isClosed()) {
                try {
                    socket.close();
                } catch (IOException e) {
                    if (node.isRunning()) {
                        System.err.printf("Erro ao fechar socket do client handler para %s: %s%n", socket.getRemoteSocketAddress(), e.getMessage());
                    }
                }
            }
        }
    }

    private void processMessage(String message) {
        System.out.printf("Mensagem recebida de %s: \"%s\"%n", socket.getRemoteSocketAddress(), message);
        String[] parts = message.split(" ", 4);

        if (parts.length < 3) {
            System.err.println("Formato de mensagem invalido recebido: " + message);
            return;
        }

        String originFullAddress = parts[0];
        int messageClock;
        try {
             messageClock = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
             System.err.println("Clock invalido na mensagem recebida: " + parts[1]);
             return;
        }

        node.updateClockOnReceive(messageClock);

        String type = parts[2];
        String argsString = (parts.length > 3) ? parts[3] : "";

        String[] originAddrParts = originFullAddress.split(":");
        if (originAddrParts.length == 2) {
            try {
                String originHost = originAddrParts[0];
                int originPort = Integer.parseInt(originAddrParts[1]);
                node.updatePeerFromDirectMessage(originHost, originPort, messageClock, type.equals("BYE"));
            } catch (NumberFormatException ex) {
                System.err.println("Porta invalida no endereco de origem: " + originFullAddress);
            }
        } else {
            System.err.println("Endereco de origem invalido: " + originFullAddress);
        }


        switch (type) {
            case "HELLO":
                System.out.printf("Mensagem HELLO de %s processada.%n", originFullAddress);
                break;
            case "BYE":
                System.out.printf("Mensagem BYE de %s processada.%n", originFullAddress);
                break;
            case "GET_PEERS":
                handleGetPeers(originFullAddress);
                break;
            case "PEER_LIST":
                handlePeerList(argsString);
                break;
            case "LS":
                handleLS(originFullAddress);
                break;
            case "DL":
                handleDL(originFullAddress, argsString);
                break;
            default:
                System.out.println("Tipo de mensagem desconhecido recebido: " + type);
        }
    }

    private void handleGetPeers(String origin) {
    String responsePayload = node.buildPeerListResponse(origin);
    String[] parts = responsePayload.split(" ", 2);
    if (parts.length == 2) {
        sendResponse(parts[0], parts[1]); 
    } else {
        sendResponse("PEER_LIST", "0");
    }
}

    private void handlePeerList(String argsString) {
        String[] parts = argsString.split(" ", 2);
        if (parts.length < 1) {
             System.err.println("Argumentos invalidos para PEER_LIST: " + argsString);
             return;
        }
        int totalPeersInList;
        try {
            totalPeersInList = Integer.parseInt(parts[0]);
        } catch (NumberFormatException e) {
             System.err.println("Contagem invalida em PEER_LIST: " + parts[0]);
             return;
        }

        if (totalPeersInList == 0) {
            return;
        }

        if (parts.length < 2 || parts[1].trim().isEmpty()) {
            if(totalPeersInList > 0) System.err.println("PEER_LIST indica " + totalPeersInList + " peers, mas lista esta faltando.");
            return;
        }

        String[] peerEntries = parts[1].trim().split(" ");
        if (peerEntries.length != totalPeersInList) {
            System.err.printf("PEER_LIST: contagem declarada (%d) difere do numero de entradas recebidas (%d)%n", totalPeersInList, peerEntries.length);
        }

        for (String peerEntry : peerEntries) {
            String[] peerData = peerEntry.split(":");
             if (peerData.length != 4) {
                 System.err.println("Formato de entrada de peer invalido em PEER_LIST: " + peerEntry);
                 continue;
             }
            String host = peerData[0];
            try {
                int port = Integer.parseInt(peerData[1]);
                PeerStatus status = PeerStatus.valueOf(peerData[2].toUpperCase());
                int peerClock = Integer.parseInt(peerData[3]);
                node.updatePeerFromPeerList(host, port, status, peerClock);
            } catch (NumberFormatException e) {
                System.err.println("Porta ou clock invalido em PEER_LIST entry: " + peerEntry + " -> " + e.getMessage());
            } catch (IllegalArgumentException e) {
                System.err.println("Status invalido em PEER_LIST entry: " + peerData[2]);
            }
        }
    }

    private void handleLS(String origin) {
        File[] localFiles;
        try {
            java.lang.reflect.Field sharedDirField = node.getClass().getDeclaredField("sharedDir");
            sharedDirField.setAccessible(true);
            File actualSharedDirFile = (File) sharedDirField.get(node);
            localFiles = actualSharedDirFile.listFiles(f -> f.isFile() && !f.getName().contains(" "));
            if (localFiles == null) {
                localFiles = new File[0];
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
            System.err.println("Error accessing sharedDir reflection for LS: " + e.getMessage());
            localFiles = new File[0];
        }


        StringBuilder fileListArgs = new StringBuilder();
        fileListArgs.append(localFiles.length);
        for (File file : localFiles) {
            fileListArgs.append(" ").append(file.getName()).append(":").append(file.length());
        }
        sendResponse("LS_LIST", fileListArgs.toString());
    }

    private void handleDL(String origin, String argsString) {
        String[] dlArgs = argsString.split(" ", 3);
        if (dlArgs.length < 3) {
            System.err.println("Argumentos invalidos para o comando DL: " + argsString);
            sendResponse("FILE", "UNKNOWN", "0", "0", "ERROR_BAD_REQUEST");
            return;
        }
        String fileName = dlArgs[0];
        int requestedChunkSize;
        int chunkIndex;
        try {
            requestedChunkSize = Integer.parseInt(dlArgs[1]);
            chunkIndex = Integer.parseInt(dlArgs[2]);
        } catch (NumberFormatException e) {
            System.err.println("Argumentos de chunk invalidos para DL: " + argsString);
            sendResponse("FILE", fileName, "0", "0", "ERROR_BAD_REQUEST");
            return;
        }

        try {
            java.lang.reflect.Field sharedDirField = node.getClass().getDeclaredField("sharedDir");
            sharedDirField.setAccessible(true);
            File actualSharedDirFile = (File) sharedDirField.get(node);
            Path filePath = Paths.get(actualSharedDirFile.getAbsolutePath(), fileName);

            if (!Files.exists(filePath) || !Files.isReadable(filePath) || Files.isDirectory(filePath)) {
                System.err.println("Arquivo " + fileName + " nao encontrado ou inacessivel no diretorio compartilhado: " + filePath);
                sendResponse("FILE", fileName, "0", String.valueOf(chunkIndex), "ERROR_NOT_FOUND");
                return;
            }

            try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "r")) {
                long fileSize = raf.length();
                long offset = (long) chunkIndex * requestedChunkSize;

                if (offset >= fileSize) {
                    sendResponse("FILE", fileName, "0", String.valueOf(chunkIndex), "ERROR_CHUNK_OUT_OF_BOUNDS");
                    return;
                }

                raf.seek(offset);
                long bytesToRead = Math.min(requestedChunkSize, fileSize - offset);
                byte[] chunkBytes = new byte[(int) bytesToRead];
                raf.readFully(chunkBytes);

                String base64Content = Base64.getEncoder().encodeToString(chunkBytes);
                sendResponse("FILE", fileName, String.valueOf(bytesToRead), String.valueOf(chunkIndex), base64Content);
            }

        } catch (NoSuchFieldException | IllegalAccessException e) {
            System.err.println("Error accessing sharedDir reflection for DL: " + e.getMessage());
            sendResponse("FILE", fileName, "0", String.valueOf(chunkIndex), "ERROR_SERVER_ISSUE");
        } catch (IOException e) {
            System.err.println("Erro ao ler arquivo " + fileName + " para DL: " + e.getMessage());
            sendResponse("FILE", fileName, "0", String.valueOf(chunkIndex), "ERROR_READ_FAILED");
        }
    }

    private void sendResponse(String responseType, String... args) {
        try {
            if (socket.isClosed()) {
                System.err.println("Tentativa de enviar resposta em socket fechado.");
                return;
            }
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            int responseClock = node.incrementClockForSend();
            StringBuilder fullResponse = new StringBuilder();
            fullResponse.append(node.getAddress()).append(" ")
                        .append(responseClock).append(" ")
                        .append(responseType);
            for (String arg : args) {
                fullResponse.append(" ").append(arg);
            }

            String responseString = fullResponse.toString();
            if (responseType.equals("FILE") && responseString.length() > 200) {
                 System.out.printf("Enviando resposta para %s: \"%s...\"%n", socket.getRemoteSocketAddress(), responseString.substring(0, 200));
            } else {
                 System.out.printf("Enviando resposta para %s: \"%s\"%n", socket.getRemoteSocketAddress(), responseString.trim());
            }
            out.println(responseString);
        } catch (IOException e) {
            if (node.isRunning()) {
                 System.err.printf("Erro ao enviar resposta '%s' para %s: %s%n", responseType, socket.getRemoteSocketAddress(), e.getMessage());
            }
             if (!socket.isClosed()) { try { socket.close(); } catch (IOException ignored) {} }
        }
    }

    private void sendRawResponse(String rawResponseWithHeader) {
         try {
             if (socket.isClosed()) {
                 System.err.println("Tentativa de enviar resposta raw em socket fechado.");
                 return;
             }
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);

             String[] currentHeaderParts = rawResponseWithHeader.split(" ", 3);
             int freshClock = node.incrementClockForSend();
             String finalResponse = String.format("%s %d %s", node.getAddress(), freshClock, currentHeaderParts[2]);

             System.out.printf("Enviando resposta para %s: \"%s\"%n", socket.getRemoteSocketAddress(), finalResponse.trim());
             out.println(finalResponse);
         } catch (IOException e) {
             if (node.isRunning()) {
                  System.err.printf("Erro ao enviar resposta raw para %s: %s%n", socket.getRemoteSocketAddress(), e.getMessage());
             }
              if (!socket.isClosed()) { try { socket.close(); } catch (IOException ignored) {} }
         }
    }
}