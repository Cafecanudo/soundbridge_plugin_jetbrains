# CLAUDE.md — SoundBridge Plugin (JetBrains / IntelliJ)

Contexto para o Claude Code trabalhar neste repositório.

## O que é este repo

Um **plugin para IDEs JetBrains** que transmite arquivos por **áudio** direto da IDE. O usuário
clica com o botão direito num arquivo do projeto → **"Enviar por Áudio"** → abre uma janela com
configurações, barra de progresso, log ao vivo e botão de retentar.

Faz parte do projeto **SoundBridge** (transferência de arquivos usando só som, via modem OFDM).
Competição acadêmica (prazo jan/2027); o plugin vale como **"implementação diferente"**.

- **Gradle + IntelliJ Platform Plugin.** **Kotlin.**
- Alvo inicial: **IntelliJ IDEA Ultimate**, mas escrito para funcionar em **qualquer IDE JetBrains**
  (usar apenas APIs da IntelliJ Platform, sem dependências específicas de produto).

## Decisão de arquitetura (JÁ TOMADA — não reabrir sem motivo)

**O plugin NÃO reimplementa a camada de áudio.** Ele chama o pacote Python **`soundbridge-tx`**
(publicado no PyPI) via **subprocess** (Opção A). O plugin orquestra: monta o comando, executa,
captura a saída e mostra o progresso/log.

- **Requisito do usuário:** Python + `soundbridge-tx[live]` instalados. O plugin deve **detectar**
  isso e orientar a instalação (`pip install soundbridge-tx[live]`) se faltar.
- Evolução futura (opcional, não agora): portar o core para Kotlin nativo.

## Regra de ouro do projeto

1. **Conversa técnica começa como brainstorming.** Roteiro + opções + trade-offs primeiro. NÃO
   escrever código até uma decisão explícita.
2. **Edições cirúrgicas:** citar o texto exato e usar "Substitua por" / "Adicione ABAIXO". Nunca
   "por volta da linha X". Se >70% de um arquivo/função muda, gerar inteiro.
3. **Sem comentários no código** — explicar as decisões no chat.
4. **Análise crítica ativa:** apontar riscos e discordar com base técnica. Concordância passiva tem
   valor zero.
5. **PT-BR** nas conversas. Termos técnicos em inglês quando são o padrão.
6. **Um passo por vez;** validar cada etapa (buildar/rodar o plugin na IDE) antes de avançar.

> **Metodologia de iteração:** o plugin builda/roda só na máquina do usuário (o SDK IntelliJ não é
> testável no ambiente do assistente). O assistente escreve o Kotlin; o usuário builda/testa na IDE
> e traz os erros. Iterar como se fez com o C++ do RX.

## Componentes do plugin (Kotlin)

| Componente | Papel |
|---|---|
| `plugin.xml` | registra a Action (menu de contexto) e o Settings; compatibilidade com IDEs JetBrains |
| `SendByAudioAction` | a ação "Enviar por Áudio" no menu de contexto de um arquivo |
| `SendDialog` | a janela de envio: config selecionável + barra de progresso + log + botão retentar |
| `SoundbridgeRunner` | executa o `soundbridge-tx` (subprocess), lê o stdout (JSON) linha a linha |
| `SoundbridgeSettings` | as configurações fixas (persistidas via `PersistentStateComponent`) |
| `SettingsConfigurable` | a tela de Settings da IDE (Preferences → Tools → SoundBridge) |

### Fluxo
1. Botão direito num arquivo no Project View → **"Enviar por Áudio"**.
2. Abre o `SendDialog` (parâmetros selecionáveis).
3. Ao **Enviar**: o `SoundbridgeRunner` roda `soundbridge-tx --in <arquivo> --progress-json ...`,
   lê o stdout JSON, atualiza a barra e o log ao vivo.
4. Se `error`: mostra **Retentar** (reexecuta com os mesmos parâmetros).

## Divisão dos parâmetros (DECISÃO TOMADA)

**Fixos — nos Settings do plugin (uma vez):** comando do `soundbridge-tx` (ou caminho do Python),
`band-high` (22000), `stereo` (sim), `resync`, `parity`, `peak`, `guard` (avançados, com defaults).

**Selecionáveis — na Janela de Envio (a cada envio):**
- **Device** de saída (dropdown, via `--list-devices-json`)
- **Modo:** AUTO (recomendado) ou manual → se manual: **modulação** + **FEC**
- **zip** (checkbox), **name** (default = nome do arquivo), **profile** (opcional),
  **copymemory** (checkbox)

## Como o plugin consome o pacote (contrato)

O plugin depende de duas saídas estruturadas do `soundbridge-tx` (a serem adicionadas na versão
0.2.0 do pacote, no repo `soundbridge_tx`):

### `--list-devices-json`
Retorna os devices em JSON (para popular o dropdown):
```json
[{"index": 12, "name": "...", "channels": 2, "sample_rate": 48000, "api": "Windows WASAPI", "default": false}, ...]
```

### `--progress-json`
Emite eventos como **linhas JSON** no stdout (uma por linha; desliga a barra ANSI):
```json
{"event": "start", "file": "arquivo.zip", "bytes": 9029}
{"event": "auto", "modulation": "1024-QAM", "fec": "r12"}
{"event": "modulating", "modulation": "1024-QAM"}
{"event": "progress", "percent": 45.2, "elapsed": 1.1, "total": 2.4}
{"event": "done", "crc32": "0xC57F5DA1", "bytes": 9029}
{"event": "error", "message": "..."}
```
O `SoundbridgeRunner` lê linha a linha, faz parse do JSON e mapeia:
`progress.percent` → barra; `start`/`auto`/`modulating` → log; `done` → sucesso; `error` → falha.

## Parâmetros do soundbridge-tx (referência do comando montado)

`--in ARQUIVO` (ou `--text`) · `--play --device N` (ou `--out X.wav`) · `--stereo` ·
`--band-high N` · `--auto` (ou `--qam16/64/256/1024`) · `--fec none/r12/r23/r34` ·
`--resync off/10/25/5` · `--parity off/8/16/32` · `--zip` · `--name` · `--profile` ·
`--copymemory` · `--list-devices-json` · `--progress-json`.

**Modo `--auto`:** ≤12KB → 1024-QAM+r12; ≤100KB → 256-QAM+r34; acima → 64-QAM+r12.

## Ordem de implementação

1. **Pré-requisito (no repo `soundbridge_tx`):** adicionar `--list-devices-json` e `--progress-json`,
   publicar `soundbridge-tx 0.2.0`. **O plugin depende disso.**
2. **Esqueleto:** projeto Gradle + IntelliJ Platform Plugin, `plugin.xml`, a `SendByAudioAction`
   aparecendo no menu de contexto (sem lógica).
3. **Settings:** `SoundbridgeSettings` + `SettingsConfigurable`.
4. **SendDialog:** a janela com os parâmetros selecionáveis + dropdown de devices.
5. **SoundbridgeRunner:** subprocess, ler o JSON, alimentar barra + log.
6. **Retentar** + tratamento de erros + detecção de ambiente (Python/pacote instalados).

## Contexto do projeto SoundBridge

- **RX (receptor):** app C++/Qt no repo `github.com/Cafecanudo/soundbridge`.
- **TX (transmissor):** pacote Python `soundbridge-tx` no repo `github.com/Cafecanudo/soundbridge_tx`
  (o que este plugin chama).
- **Modulação recomendada:** 64-QAM (~12 KB/s, robusto). O `--auto` escolhe pelo tamanho.
- O device de saída do plugin deve estar ligado ao cabo de áudio que vai para o PC receptor.

## Armadilhas a antecipar

- **Detecção de ambiente:** rodar `soundbridge-tx --list-devices-json` no início; se falhar (Python
  ou pacote ausente), mostrar mensagem clara com o comando de instalação.
- **Subprocess no Windows:** cuidado com o encoding do stdout (usar UTF-8) e o buffer (ler linha a
  linha, com flush do lado Python garantido pelo `--progress-json`).
- **Compatibilidade entre IDEs JetBrains:** não usar APIs específicas do IntelliJ IDEA; declarar a
  compatibilidade correta no `plugin.xml` (sem `<depends>` de módulos específicos de produto além
  do `com.intellij.modules.platform`).
- **Threading:** o subprocess roda fora da EDT; atualizar a UI (barra/log) via
  `ApplicationManager.getApplication().invokeLater { ... }`.
