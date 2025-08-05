import java.util.InputMismatchException;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        if (args.length < 3) {
            System.out.println("Uso: java Main <endereco:porta> <arquivo_vizinhos.txt> <diretorio_compartilhado>");
            System.out.println("Exemplo: java Main 127.0.0.1:5000 peers.txt shared_files");
            return;
        }

        Node node = null;
        try {
            node = new Node(args[0], args[1], args[2]);
        } catch (IllegalArgumentException e) {
            System.err.println("Erro ao inicializar o no: " + e.getMessage());
            System.exit(1);
        }

        Thread serverThread = new Thread(node::startServer);
        serverThread.start();

        try {
            Thread.sleep(200);
        } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }


        Scanner scanner = new Scanner(System.in);
        boolean keepRunning = true;

        while (keepRunning && node.isRunning()) {
            printMenu();
            int choice = -1;
            try {
                if (scanner.hasNextInt()){
                    choice = scanner.nextInt();
                } else {
                    System.out.println("Entrada invalida. Por favor, insira um numero.");
                }
            } catch (InputMismatchException e) {
                System.out.println("Entrada invalida. Por favor, insira um numero.");
            } finally {
                 scanner.nextLine();
            }

            if (!node.isRunning()) {
                keepRunning = false;
                break;
            }

            switch (choice) {
                case 1:
                    node.listPeers();
                    System.out.print("Escolha um peer pelo numero para enviar HELLO (ou 0 para voltar): ");
                    int peerChoice = -1;
                     try {
                        if (scanner.hasNextInt()) {
                            peerChoice = scanner.nextInt();
                        } else {
                            System.out.println("Entrada invalida. Por favor, insira um numero.");
                        }
                     } catch (InputMismatchException e) {
                         System.out.println("Entrada invalida. Por favor, insira um numero.");
                     } finally {
                         scanner.nextLine();
                     }

                    if (peerChoice > 0) {
                        node.sendHello(peerChoice - 1);
                    } else if (peerChoice == 0) {
                         System.out.println("Voltando ao menu...");
                    } else {
                         if (peerChoice != -1)
                            System.out.println("Selecao invalida.");
                    }
                    break;
                case 2:
                    node.getPeers();
                    break;
                case 3:
                    node.listLocalFiles();
                    break;
                case 4:
                    node.searchAndDownloadFiles(scanner);
                    break;
                case 5:
                    node.displayStatistics();
                    break;
                case 6:
                    System.out.print("Digite novo tamanho de chunk: ");
                    try {
                        int newSize = scanner.nextInt();
                        node.setChunkSize(newSize);
                    } catch (InputMismatchException e) {
                        System.out.println("Entrada invalida. Por favor, insira um numero.");
                    } finally {
                        scanner.nextLine();
                    }
                    break;
                case 9:
                    node.exit();
                    keepRunning = false;
                    break;
                default:
                    if (choice != -1) {
                        System.out.println("Opcao invalida. Tente novamente.");
                    }
            }
        }

        System.out.println("Fechando scanner e terminando a aplicacao.");
        scanner.close();

         try {
             if (serverThread.isAlive()) {
                serverThread.join(1000);
             }
         } catch (InterruptedException e) {
             Thread.currentThread().interrupt();
             System.err.println("Interrompido ao aguardar thread do servidor.");
         }
         if (serverThread.isAlive()){
             System.err.println("Thread do servidor nao terminou, pode haver recursos presos.");
         }

        System.out.println("Aplicacao principal encerrada.");
    }

    private static void printMenu() {
        System.out.println("\n--- Menu Principal ---");
        System.out.println("[1] Listar peers (e enviar HELLO)");
        System.out.println("[2] Obter peers (enviar GET_PEERS)");
        System.out.println("[3] Listar arquivos locais");
        System.out.println("[4] Buscar arquivos");
        System.out.println("[5] Exibir estatisticas");
        System.out.println("[6] Alterar tamanho de chunk");
        System.out.println("[9] Sair");
        System.out.print("> ");
    }
}