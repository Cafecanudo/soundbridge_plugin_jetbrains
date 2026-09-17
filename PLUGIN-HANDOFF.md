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

O TX (transmissor) já é um pacote Python publicado no PyPI (`pip install soundbridge-tx`).
O plugin **não reimplementa** a camada de áudio (OFDM/FEC/QAM) — ele **orquestra**: monta o
comando, executa o `soundbridge-tx`, captura a saída e mostra o progresso/log.

- **Vantagem:** reusa todo o código Python testado e validado no cabo. Plugin fino e rápido.
- **Requisito:** Python + o pacote `soundbridge-tx` instalados na máquina do usuário. O plugin
  deve detectar isso e orientar a instalação se faltar.
- **Evolução futura (opcional):** portar o core para Kotlin nativo (sem Python). Fica para depois;
  o plugin da Opção A já entrega o produto.

---

## 3. O pacote `soundbridge-tx` (o que o plugin chama)

Repo: `github.com/Cafecanudo/soundbridge_tx` · PyPI: `pip install soundbridge-tx`
(extra `[live]` para tocar ao vivo: `pip install soundbridge-tx[live]`).

Invocação: `python -m soundbridge_tx.cli <args>` ou o entry-point `soundbridge-tx <args>`.

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

## 4. Extensões a fazer NO PACOTE (base do plugin) — PENDENTE

Para o plugin consumir a saída de forma robusta (a "opção rica" de progresso), adicionar ao
`cli.py` do `soundbridge-tx` (e publicar uma nova versão, ex. 0.2.0):

### `--list-devices-json`
Lista os devices em JSON (o plugin popula o dropdown facilmente):
```json
[{"index": 12, "name": "...", "channels": 2, "sample_rate": 48000, "api": "Windows WASAPI", "default": false}, ...]
```

### `--progress-json`
Emite eventos de progresso como **linhas JSON** no stdout (uma por linha), em vez da barra ANSI.
Quando ligado, desliga a barra ANSI (o stdout tem só JSON, fácil de parsear):
```json
{"event": "start", "file": "arquivo.zip", "bytes": 9029}
{"event": "auto", "modulation": "1024-QAM", "fec": "r12"}
{"event": "modulating", "modulation": "1024-QAM"}
{"event": "progress", "percent": 45.2, "elapsed": 1.1, "total": 2.4}
{"event": "progress", "percent": 100.0, "elapsed": 2.4, "total": 2.4}
{"event": "done", "crc32": "0xC57F5DA1", "bytes": 9029}
{"event": "error", "message": "..."}
```
O plugin mapeia: `progress.percent` → barra; `start`/`auto`/`modulating` → log; `done` → sucesso;
`error` → falha (mostra o botão retentar).

**Nota:** o `cli.py` já tem a classe `_Progress` (pontos `run`/`ok`) e o `_play` (calcula `frac`).
Basta adicionar uma função `_emit(event, **kw)` que faz `print(json.dumps({...}), flush=True)` e
chamá-la nesses pontos quando `args.progress_json`.

---

## 5. Arquitetura do plugin (Kotlin)

| Componente | Papel |
|---|---|
| `plugin.xml` | registra a Action (menu de contexto) e o Settings; declara compatibilidade com IDEs JetBrains |
| `SendByAudioAction` | a ação "Enviar por Áudio" no menu de contexto de um arquivo |
| `SendDialog` | a janela de envio: configurações + barra de progresso + log + botão retentar |
| `SoundbridgeRunner` | executa o `soundbridge-tx` (subprocess), lê o stdout (JSON) linha a linha, emite eventos |
| `SoundbridgeSettings` | as configurações fixas (persistidas via `PersistentStateComponent`) |
| `SettingsConfigurable` | a tela de Settings da IDE (Preferences → Tools → SoundBridge) |

### Fluxo
1. Botão direito num arquivo no Project View → **"Enviar por Áudio"** (`SendByAudioAction`).
2. Abre o `SendDialog` com os parâmetros selecionáveis (device, auto/manual, zip, name, profile...).
3. Ao clicar **Enviar**: o `SoundbridgeRunner` monta o comando (`soundbridge-tx --in <arquivo>
   --progress-json ...`), executa, lê o stdout JSON, atualiza a barra e o log ao vivo.
4. Se `error`: mostra o botão **Retentar** (reabre/reexecuta com os mesmos parâmetros).

---

## 6. Divisão dos parâmetros (DECISÃO TOMADA)

**Fixos — nos Settings do plugin (configurados uma vez):**
- Comando do `soundbridge-tx` (ou caminho do Python) — como invocar o pacote
- `band-high` (22000), `stereo` (sim)
- `resync`, `parity`, `peak`, `guard` (avançados, com defaults)

**Selecionáveis — na Janela de Envio (a cada envio):**
- **Device** de saída (dropdown, populado via `--list-devices-json`)
- **Modo:** AUTO (recomendado) ou manual
  - se manual: **modulação** (qpsk/16qam/64qam/256qam/1024qam) + **FEC** (r12/r23/r34)
- **zip** (checkbox)
- **name** (opcional; default = nome do arquivo)
- **profile** (opcional)
- **copymemory** (checkbox)

---

## 7. Requisitos do sistema (o plugin deve tratar)

- **Python** instalado + **`soundbridge-tx`** instalado (com o extra `[live]` para `--play`).
- O plugin deve **detectar** se o comando funciona (ex. rodar `soundbridge-tx --list-devices-json`
  no início; se falhar, mostrar mensagem orientando `pip install soundbridge-tx[live]`).
- O **device de saída** precisa estar conectado ao cabo de áudio que vai para o PC receptor
  (o app RX C++ do repo `soundbridge`).

---

## 8. Ordem sugerida de implementação

1. **No pacote:** adicionar `--list-devices-json` e `--progress-json` ao `cli.py`, testar, publicar
   `soundbridge-tx 0.2.0` no PyPI. (Base para o plugin.)
2. **Esqueleto do plugin:** projeto Gradle + IntelliJ Platform Plugin, `plugin.xml`, a
   `SendByAudioAction` aparecendo no menu de contexto (ainda sem lógica).
3. **Settings:** `SoundbridgeSettings` + `SettingsConfigurable` (o comando do tx, os fixos).
4. **SendDialog:** a janela com os parâmetros selecionáveis + o dropdown de devices.
5. **SoundbridgeRunner:** executar o subprocess, ler o JSON, alimentar barra + log.
6. **Retentar** + tratamento de erros + detecção de ambiente (Python/pacote instalados).

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
