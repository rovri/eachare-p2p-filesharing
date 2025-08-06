# EACHare - Sistema de Compartilhamento de Arquivos P2P

> Um sistema de compartilhamento de arquivos peer-to-peer (P2P) construído em Java, com descoberta de peers, downloads paralelos de múltiplas fontes e consistência de estado baseada em Relógios de Lamport.

## Sobre o Projeto

**EACHare** é um sistema de compartilhamento de arquivos peer-to-peer (P2P) desenvolvido em Java.

O sistema permite que nós (peers) em uma rede distribuída descubram uns aos outros, busquem por arquivos e realizem downloads paralelos e fragmentados de múltiplas fontes, simulando um ambiente real de troca de arquivos.

## Funcionalidades Principais

#### 1. Descoberta de Peers e Gerenciamento de Estado
*   Utiliza um protocolo baseado em mensagens (`HELLO`, `GET_PEERS`, `BYE`) para manter uma lista de peers conhecidos na rede.
*   Garante a consistência do estado dos peers (ONLINE/OFFLINE) através de **Relógios de Lamport**, assegurando que informações mais recentes sobre o estado de um peer sempre prevaleçam sobre as mais antigas.

#### 2. Busca de Arquivos na Rede
*   Capacidade de buscar arquivos (`LS`) em todos os peers online, agrupando dinamicamente arquivos idênticos (mesmo nome e tamanho) que estejam disponíveis em diferentes fontes.
*   Apresenta ao usuário uma lista consolidada, indicando todos os peers que possuem uma cópia de um determinado arquivo.

#### 3. Download Paralelo e Fragmentado (Multi-source)
*   Arquivos são baixados em **chunks** (pedaços) de tamanho customizável pelo usuário.
*   O sistema distribui as requisições de download dos chunks entre múltiplos peers que possuem o arquivo (usando uma estratégia **Round-Robin**), acelerando significativamente a velocidade de transferência.
*   Um `ExecutorService` gerencia um pool de threads para baixar os chunks em paralelo, sem bloquear a interface principal do usuário.

#### 4. Coleta de Estatísticas de Desempenho
*   Mede o tempo de download para cada arquivo e coleta estatísticas detalhadas.
*   Calcula e exibe o tempo médio e o desvio padrão dos downloads, agrupando os dados pela tripla: `(tamanho do chunk, tamanho do arquivo, número de peers-fonte)`. Isso permite uma análise precisa do desempenho da rede sob diferentes condições.

## Detalhes Técnicos e Arquitetura
*   **Servidor TCP Multithread:** Cada nó opera como um servidor TCP que lida com múltiplas conexões de clientes simultaneamente, utilizando uma thread dedicada por requisição (`ClientHandler`).
*   **Gerenciamento de Concorrência:** Utiliza `ExecutorService` para gerenciar o pool de threads dos downloads paralelos e estruturas de dados thread-safe (`CopyOnWriteArrayList`, `ConcurrentHashMap`) para garantir a integridade em um ambiente concorrente.
*   **Protocolo Textual e Transferência em Base64:** A comunicação entre os peers é feita através de um protocolo textual simples sobre TCP. Para garantir a transferência segura de dados binários (conteúdo dos arquivos) dentro deste protocolo, os chunks são codificados em **Base64**.

## Tecnologias Utilizadas
*   **Java**
*   **Java Networking (Sockets TCP)**
*   **Java Concurrency API (ExecutorService, Thread, etc.)**

## Como Compilar e Executar

**Pré-requisitos:**

*   JDK (Java Development Kit) instalado e configurado no PATH.
*   Arquivos `.java` (Node.java, Main.java, Peer.java, etc.).
*   Arquivo `peers.txt` (contendo endereços de outros peers, um por linha, ex: `127.0.0.1:5001`).
*   Um diretório para arquivos compartilhados (ex: `arquivos_compartilhados`), que pode conter alguns arquivos de teste.

**Compilação:**

1.  Abra um terminal ou prompt de comando.
2.  Navegue até o diretório "src" que contém todos os arquivos `.java`.
3.  Execute o comando de compilação:
    ```bash
    javac *.java
    ```

**Execução (Teste Local com Múltiplos Peers):**

1.  Crie e certifique-se de que o arquivo `peers.txt` e o diretório `arquivos_compartilhados` estão no mesmo local dos arquivos `.class` gerados.
2.  Abra **múltiplas janelas** de terminal (uma para cada peer que deseja simular).
4.  Execute o comando `java Main ...` em cada terminal, **usando uma porta diferente** para cada um. Para testar a função de busca é recomendo que os peers utilizem diretórios diferentes.Exemplo para 3 peers:

    *   **Terminal 1:**
        ```bash
        java Main 127.0.0.1:5000 peers.txt arquivos_compartilhados
        ```
    *   **Terminal 2:**
        ```bash
        java Main 127.0.0.1:5001 peers.txt arquivos_compartilhados2
        ```
    *   **Terminal 3:**
        ```bash
        java Main 127.0.0.1:5002 peers.txt arquivos_compartilhados3
        ```

    *(Ajuste os nomes `peers.txt` e `arquivos_compartilhados` se forem diferentes).*

Cada terminal agora representa um peer na rede, pronto para receber comandos do menu.
