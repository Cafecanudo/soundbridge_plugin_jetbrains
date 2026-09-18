# SoundBridge — Handoff do Plugin IntelliJ (chat dedicado)

Documento para desenvolver o **plugin JetBrains** do SoundBridge em uma sessão separada.
Cole este documento no início do novo chat.

---

## 1. O que é

Um plugin para IDEs JetBrains que transmite arquivos por **áudio** direto da IDE
("enviar por áudio"). O usuário clica com o botão direito num arquivo do projeto →
**"Enviar por Áudio"** → abre uma janela de envio com configurações, barra de progresso,
log ao vivo e botão de retentar.

Vale como **"implementação diferente"** na competição acadêmica (o SoundBridge é um projeto
de transferência de arquivos usando só som).

### Escopo
- **Começar pelo IntelliJ IDEA (Ultimate)**, mas escrito para funcionar em **qualquer IDE
  JetBrains** (usar apenas APIs da IntelliJ Platform, sem dependências específicas de produto).
- **Gradle + IntelliJ Platform Plugin** (o padrão moderno da JetBrains). **Kotlin.**

---

## 2. A decisão de arquitetura (JÁ TOMADA)

**Opção A — o plugin chama o pacote pip `soundbridge-tx` via subprocess.**
**Opção B — integração contra a CLI 1.0.0 publicada, parseando a saída de TEXTO (sem flags JSON).**

O TX (transmissor) já é um pacote Python publicado no PyPI (`pip install soundbridge-tx`, versão
**1.0.0**, `requires_python >=3.10`). O plugin **não reimplementa** a camada de áudio
(OFDM/FEC/QAM) — ele **orquestra**: monta o comando, executa o `soundbridge-tx`, captura a saída e
mostra o progresso/log.

- **Vantagem:** reusa todo o código Python testado e validado no cabo. Plugin fino e rápido.
- **Opção B (DECIDIDO):** o plugin **NÃO usa** `--list-devices-json`/`--progress-json` (não existem
  no pacote). Parseia a saída de texto da CLI 1.0.0. **Sem dependência de republicar o pacote.**
  O contrato de parse está travado a partir da saída real (ver seção 4).
- **Requisito:** Python ≥3.10 + o pacote `soundbridge-tx[live]` instalados na máquina do usuário.
  O plugin deve detectar isso e orientar a instalação se faltar.
- **Evolução futura (opcional):** portar o core para Kotlin nativo (sem Python). Fica para depois;
  o plugin da Opção A já entrega o produto.

---

## 3. O pacote `soundbridge-tx` (o que o plugin chama)

Repo: `github.com/Cafecanudo/soundbridge_tx` · PyPI: `pip install soundbridge-tx` (versão **1.0.0**)
(extra `[live]` para tocar ao vivo: `pip install soundbridge-tx[live]`).

Invocação: entry-point `soundbridge-tx <args>` (confirmado no PATH da máquina do usuário) ou
`python -m soundbridge_tx.cli <args>`. Default do campo "comando" nos Settings: `soundbridge-tx`.

### TODOS os parâmetros do soundbridge-tx

**Entrada e saída:**
| Parâmetro | Descrição |
|---|---|
| `--in ARQUIVO` | arquivo a transmitir. Também aceita uma **pasta** (envia todos, um a um) |
| `--text "..."` | envia um texto direto em vez de um arquivo |
| `--out ARQUIVO.wav` | gera um WAV em vez de tocar ao vivo |
| `--play` | toca ao vivo na placa de som (requer o extra `[live]`) |
| `--device N` | índice do dispositivo de saída (com `--play`) |
| `--list-devices` | lista os dispositivos de saída (texto) e encerra |
| `--size N` | tamanho de um arquivo de teste interno (quando não há `--in` nem `--text`) |

**Modulação e correção de erro:**
| Parâmetro | Descrição |
|---|---|
| `--auto` | **escolhe modulação e FEC automaticamente pelo tamanho** (recomendado) |
| `--qam16` | 16-QAM (4 bits/subportadora) |
| `--qam64` | 64-QAM (6 bits — teto robusto) |
| `--qam256` | 256-QAM (8 bits — experimental, arquivos pequenos-médios) |
| `--qam1024` | 1024-QAM (10 bits — experimental, arquivos muito pequenos) |
| _(nenhum)_ | QPSK (2 bits — padrão, mais robusto) |
| `--fec MODO` | `none`, `r12` (padrão), `r23`, `r34` |
| `--resync MODO` | `off`, `10` (padrão), `25`, `5` (blocos) |
| `--parity MODO` | `off`, `8`, `16` (padrão), `32` (grupos) |

**Canal e banda:**
| Parâmetro | Descrição |
|---|---|
| `--stereo` | usa os 2 canais (2× mais rápido; recomendado) |
| `--band-high N` | frequência máxima da banda em Hz (padrão 14000; usar 22000) |
| `--peak V` | pico de amplitude (0–1) |
| `--guard S` | silêncio de guarda (segundos) no início/fim |

**Metadados (o RX usa ao salvar):**
| Parâmetro | Descrição |
|---|---|
| `--name "NOME"` | nome do arquivo salvo no RX (aceita subpasta: `docs/a.txt`) |
| `--profile NOME` | perfil de recepção (o RX resolve a pasta base) |
| `--copymemory` | o RX copia o conteúdo para a área de transferência |
| `--zip` | comprime os dados antes de enviar (o RX descomprime) |

**Múltiplos arquivos (com `--in PASTA`):**
| Parâmetro | Descrição |
|---|---|
| `--gap S` | intervalo entre arquivos (padrão 5) |

**Diagnóstico:**
| Parâmetro | Descrição |
|---|---|
| `--verbose` | detalhes de cada etapa |

### O modo `--auto` (faixas)
| Tamanho do payload | Escolha |
|---|---|
| até ~12 KB | 1024-QAM + r12 |
| até ~100 KB | 256-QAM + r34 |
| acima disso | 64-QAM + r12 (robusto) |

Se combinado com `--zip`, decide pelo tamanho comprimido.

---

## 4. Contrato de parse da CLI 1.0.0 (Opção B — TRAVADO)

O plugin **não** depende de mudanças no pacote. Ele parseia a **saída de texto** da CLI 1.0.0.
Contratos abaixo travados a partir da saída **real** na máquina do usuário (Windows/PowerShell).

### `--list-devices` → dropdown de devices
Saída real (ignorar o cabeçalho `Dispositivos de saida disponiveis:`):
```
  [12] Alto-falantes (G733 Gaming Headset)  2ch  48000Hz  [Windows WASAPI]
  [4] Alto-falantes (G733 Gaming Head  2ch  44100Hz  [MME] (default)
  [13] Alto-falantes (Realtek USB Audio)  2ch  48000Hz  [Windows WASAPI]
```
Regex por linha:
```
^\s*\[(\d+)\]\s+(.*?)\s{2,}(\d+)ch\s{2,}(\d+)Hz\s{2,}\[([^\]]+)\](\s+\(default\))?\s*$
```
→ `index`, `name` (pode vir **truncado** pela CLI), `channels`, `sampleRate`, `api`, `isDefault`.
Separador entre campos = **2+ espaços** (o nome usa espaço simples; `.*?` não-guloso corta certo).
Label no dropdown: `name — Nch — NHz — [API]`.

### Progresso / sucesso (`--play`, mesmo formato do `--out`)
Saída real:
```
auto: payload 4 bytes -> 1024-QAM + r12
[###################################] Escrevendo WAV
crc32=0xC9FDD05E  payload=4  dados=2  band_high=22000  resync=10  parity=16
```
Mapeamento do `SoundbridgeRunner`:
- Barra `[###...]` **sem percentual** → **barra INDETERMINADA (spinner)** na v1.
- `auto: payload (\d+) bytes -> (.+)` → log (decisão do auto).
- Qualquer outra linha (ex. `Escrevendo WAV`; no `--play` será outro rótulo) → **stream ao log**.
- **Linha de sucesso ("done"):**
  `crc32=(0x[0-9A-Fa-f]+)\s+payload=(\d+)\s+dados=(\d+)\s+band_high=(\d+)\s+resync=(\S+)\s+parity=(\S+)`
- **Regra final:** `exitCode==0` **e** linha `crc32=` vista → sucesso. Senão → falha → **Retentar**.

### Regras de execução do subprocess
1. **Forçar UTF-8:** env `PYTHONUTF8=1` + `PYTHONIOENCODING=utf-8`; ler stdout como UTF-8 (sem isso,
   mojibake nos nomes/mensagens).
2. **SEMPRE `--device N`.** `--play` sem device é **interativo** (stdin) → trava o subprocess.
3. **QPSK = ausência de flag** (não existe `--qpsk`).
4. **Dropdown:** não pré-selecionar o `(default)` do sistema (pode ser 44100Hz). Ordem: último
   device usado → **WASAPI 48000** → default. O RX exige **48000 Hz**; avisar se ≠48000.

---

## 5. Arquitetura do plugin (Kotlin)

| Componente | Papel |
|---|---|
| `plugin.xml` | registra a Action (menu de contexto) e o Settings; declara compatibilidade com IDEs JetBrains |
| `SendByAudioAction` | a ação "Enviar por Áudio" no menu de contexto de um arquivo |
| `SendDialog` | a janela de envio: configurações + barra de progresso + log + botão retentar |
| `SoundbridgeRunner` | executa o `soundbridge-tx` (subprocess), lê o stdout (texto) linha a linha, emite eventos |
| `SoundbridgeSettings` | as configurações fixas (persistidas via `PersistentStateComponent`) |
| `SettingsConfigurable` | a tela de Settings da IDE (Preferences → Tools → SoundBridge) |

### Fluxo
1. Botão direito num arquivo no Project View → **"Enviar por Áudio"** (`SendByAudioAction`).
2. Abre o `SendDialog` com os parâmetros selecionáveis (device, auto/manual, zip, name, profile...).
3. Ao clicar **Enviar**: o `SoundbridgeRunner` monta o comando (`soundbridge-tx --in <arquivo>
   --play --device N ...`), executa, lê o stdout de texto linha a linha, alimenta a barra
   (indeterminada) e o log ao vivo.
4. Se falha (exit≠0 / traceback): mostra o botão **Retentar** (reexecuta com os mesmos parâmetros).

---

## 6. Divisão dos parâmetros (DECISÃO TOMADA)

**Fixos — nos Settings do plugin (configurados uma vez):**
- Comando do `soundbridge-tx` (ou caminho do Python) — como invocar o pacote
- `band-high` (22000), `stereo` (sim)
- `peak`, `guard` (avançados, opcionais). `--verbose` sempre ligado.

**Selecionáveis — na Janela de Envio (a cada envio):**
- **Device** de saída (dropdown, via parse do `--list-devices`). Aceita **arquivo ou pasta** (`--in <dir>`).
- **Qualidade (preset)** — barra `SegmentedButton`: AUTO · RÁPIDO · BALANCEADO · ROBUSTO · EXPERIMENTAL ·
  CUSTOM. Preenche e trava modulação + FEC + resync + paridade; só **CUSTOM** libera; **AUTO** = `--auto`.
  (Ver `PRESETS-HANDOFF.md`.)
- **Gerar WAV** (checkbox) — `--out <pasta>/<nome>.wav`; botão vira "Salvar"; só para arquivo (off em pasta/texto).
- **zip** (default ON p/ arquivo/pasta; OFF p/ texto), **name** (default = nome do arquivo; vazio no texto),
  **profile** (opcional), **copymemory** (**só no texto**), **Mostrar log** (off default), **Auto-Enviar** (persistido).

**Fonte também pode ser texto selecionado** (menu do editor, ação `SendTextByAudioAction`): TextArea editável;
a seleção é **sempre** gravada num arquivo temp único e enviada via `--in <temp>` (+ `--zip` se marcado),
apagado ao fim — evita o bug de quoting do `ProcessBuilder` no Windows (aspas embutidas). Nome no RX =
campo Nome, ou `texto.txt` se vazio.

> **`resync`/`parity` são por-envio** (preset/CUSTOM), não mais fixos nos Settings. No AUTO não são emitidos.
> A janela persiste as últimas escolhas, exceto o **nome** e o **texto**.

---

## 7. Requisitos do sistema (o plugin deve tratar)

- **Python ≥3.10** instalado + **`soundbridge-tx`** instalado (com o extra `[live]` para `--play`).
- O plugin deve **detectar** se o comando funciona (ex. rodar `soundbridge-tx --list-devices`
  no início; se falhar, mostrar mensagem orientando `pip install soundbridge-tx[live]`).
- O **device de saída** precisa estar conectado ao cabo de áudio que vai para o PC receptor
  (o app RX C++ do repo `soundbridge`).

---

## 8. Ordem sugerida de implementação

> **Passo 0 (feito):** contrato de parse da CLI 1.0.0 travado a partir da saída real (ver seção 4).
> Sem dependência de mexer no pacote.

1. **Esqueleto do plugin:** projeto Gradle + IntelliJ Platform Plugin, `plugin.xml`, a
   `SendByAudioAction` aparecendo no menu de contexto (ainda sem lógica).
2. **Settings:** `SoundbridgeSettings` + `SettingsConfigurable` (o comando do tx, os fixos).
3. **SendDialog:** a janela com os parâmetros selecionáveis + o dropdown de devices (parse do
   `--list-devices`).
4. **SoundbridgeRunner:** executar o subprocess, ler o texto linha a linha, alimentar barra
   (indeterminada) + log, sucesso pelo `crc32=`/exitCode.
5. **Retentar** + tratamento de erros + detecção de ambiente (Python/pacote instalados).

**Metodologia:** o desenvolvimento do plugin builda/roda só na máquina do usuário (o SDK IntelliJ
não é testável no ambiente do assistente). Iterar como no C++: o assistente escreve o Kotlin, o
usuário builda/testa na IDE e traz os erros. Brainstorm-first, PT-BR, edições cirúrgicas.

---

## 9. Contexto do projeto (resumo)

- **SoundBridge:** transfere arquivos entre 2 PCs usando só áudio (modem OFDM). Competição
  acadêmica, prazo jan/2027. Throughput é o critério que mais pontua.
- **RX (receptor):** app C++/Qt no repo `github.com/Cafecanudo/soundbridge`.
- **TX (transmissor):** pacote Python `soundbridge-tx` (o que o plugin chama).
- **Modulação recomendada:** 64-QAM (~12 KB/s, robusto). O `--auto` escolhe pelo tamanho.
- Docs completos no repo `soundbridge`: `ROADMAP.md`, `SoundBridge-HANDOFF.md`,
  `DECISOES-ESTRATEGICAS.md`.

---

## 10. Preferências de trabalho (aplicar na sessão do plugin)

1. Conversa técnica começa como **brainstorming**. Nada de código até decisão explícita.
2. Roteiro + opções + trade-offs primeiro. Aguardar decisão.
3. **PT-BR.** Sem comentários no código (explicar no chat). Análise crítica ativa.
4. Edições cirúrgicas: texto exato + "Substitua por"/"Adicione ABAIXO".
5. Um passo por vez; validar cada etapa (compilar/rodar na IDE) antes de avançar.
