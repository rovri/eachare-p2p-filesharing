# eachare-p2p-filesharing

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
