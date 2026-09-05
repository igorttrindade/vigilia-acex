# Vigília

Aplicativo Android de monitoramento de fadiga para motoristas. Usa detecção de landmarks faciais **on-device** com MediaPipe Face Landmarker para estimar fechamento dos olhos (PERCLOS), taxa de piscadas, bocejos e orientação da cabeça. Produz um **score de fadiga 0–100** com máquina de estados por histerese (NORMAL → WARNING → FATIGUED) e dispara alertas sonoros quando o motorista aparenta estar fatigado.

Sessões são persistidas localmente (CSV + JSON) e sincronizadas com Supabase (Auth + Postgrest) em background via WorkManager.

## Sumário

- [Funcionalidades](#funcionalidades)
- [Como funciona](#como-funciona)
- [Stack técnica](#stack-técnica)
- [Configuração e build](#configuração-e-build)
- [Modelo de dados — o que uma sessão gera](#modelo-de-dados--o-que-uma-sessão-gera)
- [Permissões](#permissões)
- [Testes](#testes)
- [Estrutura do projeto](#estrutura-do-projeto)
- [Limitações conhecidas](#limitações-conhecidas)

## Funcionalidades

- **Detecção facial on-device** — nenhum frame de câmera sai do dispositivo. Todo o processamento roda localmente com MediaPipe FaceLandmarker (modelo bundled como asset).
- **Zoom de privacidade na preview** — a visualização exibida ao motorista é ampliada em 1.4x na UI para que passageiros de trás/lado não apareçam na tela e não se sintam gravados sem consentimento. A análise de fadiga continua vendo o frame completo (o zoom é puramente cosmético), então detecção do motorista, `pickDriverIndex` e scoring seguem inalterados.
- **Score composto de fadiga** — combina PERCLOS (percentual de olhos fechados numa janela de 30 s), taxa de piscadas (janela de 60 s) e detecção de bocejos, com pesos 65/10/15 (PERCLOS/piscadas/bocejos, soma = 90) e suavização exponencial.
- **Máquina de estados com histerese** — transições NORMAL ↔ WARNING ↔ FATIGUED exigem que o score sustente o limiar por 3–5 segundos, evitando falsos positivos em oscilações momentâneas.
- **Calibração personalizada** — nos primeiros 10 s de cada sessão, o app mede a abertura natural dos olhos do motorista e ajusta o limiar de "olho fechado" (padrão PERCLOS-70).
- **Detecção de olhar para o lado** — pausa o acúmulo de PERCLOS quando o motorista checa espelhos/painel (yaw > 25° ou pitch fora da faixa), evitando falsos alertas.
- **Adaptação a baixa luz (Fase 1)** — máquina de estados NORMAL/LOW_LIGHT/DARK que ativa pré-processamento OpenCV (CLAHE + gamma), ajusta EV/FPS/scene-mode da câmera via Camera2Interop e amplia a tolerância de perda de face em DARK.
- **Detector de olhos parcialmente fechados** — safety net paralelo ao microsono tradicional. Se o olho ficar abaixo de 45% de abertura (threshold absoluto, não calibrado) por 5 s contínuos → WARNING; 8 s → FATIGUED. Cobre o caso em que MediaPipe reporta closure "meia-boca" (blendshape 0.25-0.45) e o detector calibrado não engata.
- **Alertas sonoros** — bipe curto via `ToneGenerator` (STREAM_ALARM) na transição para WARNING/FATIGUED, com re-alerta a cada 8 s enquanto FATIGUED persistir. Contagem no histórico segue o modelo "1 evento auditivo = 1 count": escalação WARNING→FATIGUED toca som mas não conta como alerta novo; descer de FATIGUED→WARNING é silencioso (motorista está melhorando, não faz sentido re-alarmar).
- **Serviço em foreground** — monitoramento continua mesmo com a tela desligada, com WakeLock de segurança limitado a 2 h.
- **Sincronização com Supabase** — WorkManager envia sessões e telemetria para tabelas Postgres protegidas por RLS. Falhas usam backoff exponencial (30 s, até 3 tentativas).
- **Histórico local com detalhamento** — todas as sessões ficam armazenadas em `filesDir/sessions/{uuid}/`. Tocando num item do histórico, o app abre uma tela de detalhe com gráfico de score ao longo da sessão (peak-preserving, downsample para até 600 pontos) e marcadores de alarme. Para tirar os CSVs do device: `adb pull /data/data/com.vigilia.app/files/sessions/`.
- **Autenticação** — cadastro/login por e-mail e senha, com fluxo completo de "esqueci minha senha" via deep link.

## Como funciona

Pipeline em tempo real, executando ~30 fps na thread do executor de análise:

```
CameraX (frontal, 640×480)
  → LightingMonitor classifica NORMAL/LOW_LIGHT/DARK (luminância Y + sensor de luz)
  → FaceAnalyzer aplica CLAHE + gamma se necessário e roda MediaPipe FaceLandmarker
  → Extrai blendshapes (eyeBlinkLeft/Right, jawOpen), EAR geométrico e yaw/pitch da cabeça
  → FatigueScorer processa PERCLOS + blinks + yawns + look-away → score suavizado + estado
  → MonitoringService dispara alertas quando estado sobe para WARNING/FATIGUED
  → TelemetryWriter grava um registro a cada 2 s em session.csv
  → Ao parar, session_summary.json é gerado e SyncWorker sincroniza com Supabase
```

Para o detalhamento algorítmico (constantes, limiares, decisões de design), consulte [CLAUDE.md](CLAUDE.md).

## Stack técnica

| Camada | Tecnologias |
|---|---|
| Linguagem/build | Kotlin 2.3.21, Java 17, AGP 9.2.1 |
| SDK Android | `compileSdk` 37, `targetSdk` 34, `minSdk` 26 |
| UI | Jetpack Compose (BOM 2026.05.00), Material 3, Navigation Compose 2.9.8 |
| Câmera | CameraX 1.6.1 (core, camera2, lifecycle, view) |
| Visão computacional | MediaPipe Tasks Vision 0.10.14, OpenCV 4.11.0 |
| Backend | Supabase-kt 3.1.4 (Auth + Postgrest) sobre Ktor 3.1.2 |
| Background | WorkManager 2.10.1 |
| Localização | Play Services Location 21.3.0 |
| Serialização | Kotlin Serialization 2.3.21 |
| Testes | JUnit 4.13.2 + `TemporaryFolder`, sem Mockito |

## Configuração e build

### Pré-requisitos

- Android Studio Ladybug ou superior (a JBR bundled com o Studio em `C:\Program Files\Android\Android Studio\jbr` funciona; alternativamente, qualquer JDK 17+).
- Um projeto Supabase criado com as tabelas `profiles`, `sessions` e `telemetry_records` (schemas descritos abaixo) e políticas RLS aplicadas.
- Dispositivo Android físico com câmera frontal (o emulador serve, mas a câmera virtual do AVD tem detecção facial pobre).

### Configurando credenciais Supabase

Crie `local.properties` na raiz do projeto (o arquivo é `gitignored`):

```properties
sdk.dir=C\:\\Users\\seu.usuario\\AppData\\Local\\Android\\Sdk
SUPABASE_URL=https://seu-projeto.supabase.co
SUPABASE_KEY=sb_publishable_...
```

A chave usada é a `sb_publishable_*` (pública por design) — a segurança real vem das políticas RLS do lado do Supabase.

### Comandos de build

```bash
# Debug APK
./gradlew :app:assembleDebug

# Instalar em device conectado
./gradlew installDebug

# Release APK (R8 + resource shrinking habilitados; assinado com keystore local
# — RELEASE_KEYSTORE_* em local.properties; ver docs/decisions.md)
./gradlew :app:assembleRelease

# Suíte de testes JVM
./gradlew :app:testDebugUnitTest --rerun-tasks

# Teste de uma classe específica
./gradlew :app:testDebugUnitTest --tests com.vigilia.app.domain.scoring.FatigueScorerTest

# Lint
./gradlew :app:lint
```

## Modelo de dados — o que uma sessão gera

Vamos seguir um exemplo concreto. Suponha que **João Silva** (usuário registrado no Supabase, `user_id = 3f2a...`) inicia uma viagem às 19:00 e para 45 minutos depois.

### 1. Cadastro — tabela `profiles`

Ao criar a conta, um registro é `upsert`ado em `profiles`:

| Coluna | Valor | Origem |
|---|---|---|
| `id` (uuid) | `3f2a8b4c-...` | UID do Supabase Auth |
| `full_name` (text) | `João Silva` | Formulário de cadastro |

Uma linha por usuário. Referenciada por `sessions.user_id` e `telemetry_records.user_id` via FK.

### 2. Início da sessão

João abre o app, concede as permissões de câmera e localização no `SetupScreen`, mantém a calibração ligada e toca em **Iniciar**. O `MonitoringService` sobe como foreground service (ícone persistente na barra) e:

- Cria pasta local `filesDir/sessions/{uuid}/` com `session.csv` (só o cabeçalho de 27 colunas por enquanto).
- Instancia um `FatigueScorer` novo e reseta o `LightingMonitor`.
- Começa a receber frames da câmera frontal.

Nada ainda no Supabase — a sincronização acontece só ao final da sessão.

### 3. Durante a viagem — o que vai em `telemetry_records`

A cada **2 segundos**, o app grava uma linha em `session.csv`. Ao final da sessão, essas linhas viram registros em `telemetry_records` no Supabase (batches de 100 via `upsert`).

**Esquema completo da tabela `telemetry_records`** (27 colunas):

| Coluna Supabase | Tipo | Origem | Descrição |
|---|---|---|---|
| `session_id` | uuid | UUID gerado no start | Chave estrangeira para `sessions.id` |
| `user_id` | uuid | Auth do Supabase | Chave estrangeira para `profiles.id` |
| `timestamp` | bigint | `System.currentTimeMillis()` | Momento do registro (epoch ms) |
| `score` | real | `FatigueScorer` | Score suavizado 0-100 |
| `state` | text | `FatigueScorer` | `NORMAL`, `WARNING`, `FATIGUED`, `NO_FACE`, `CALIBRATING` |
| `eye_openness` | real | Média dos dois olhos | Probabilidade de olho aberto 0-1 |
| `blink_rate` | real | `FatigueScorer` | Piscadas por minuto (janela de 60 s) |
| `is_yawning` | bool | `FatigueScorer` | Bocejo detectado neste momento |
| `is_face_detected` | bool | `FaceAnalyzer` | Rosto encontrado no frame |
| `alert_active` | bool | `MonitoringService` | Alerta sonoro tocando |
| `latitude` | float8 | FusedLocationProvider | GPS, nullable |
| `longitude` | float8 | FusedLocationProvider | GPS, nullable |
| `speed` | real | FusedLocationProvider | m/s, nullable |
| `accel_x/y/z` | real | SensorManager (accel) | m/s², nullable |
| `gyro_x/y/z` | real | SensorManager (gyro) | rad/s, nullable |
| `perclos` | real | `FatigueScorer` | % de frames com olho fechado (0-1) na janela de 30 s |
| `perclos_contribution` | real | `FatigueScorer` | Parcela do PERCLOS no score final (peso 65) |
| `blink_contribution` | real | `FatigueScorer` | Parcela do desvio de piscadas no score (peso 10) |
| `yawn_contribution` | real | `FatigueScorer` | Parcela do bocejo no score (peso 15) |
| `ambient_light_lux` | real | Sensor `TYPE_LIGHT` | Lux ambiente, nullable |
| `frame_luminance` | real | `FaceAnalyzer` | Média Y do frame 0-255 |
| `lighting_mode` | text | `LightingMonitor` | `NORMAL`, `LOW_LIGHT`, `DARK` |
| `head_yaw_degrees` | real | `FaceAnalyzer` | Yaw da cabeça em graus (decomposto da matriz de transformação facial). Permite reconstruir look-away post-hoc |
| `head_pitch_degrees` | real | `FaceAnalyzer` | Pitch da cabeça em graus |

**Exemplo de linhas ao longo da viagem de João:**

**19:00:04** — segundos iniciais, calibração em andamento:
```
session_id: 7a1c...     score: 0.0        state: CALIBRATING
eye_openness: 0.82      blink_rate: 0.0   is_yawning: false
is_face_detected: true  alert_active: false
latitude: -23.5505      longitude: -46.6333   speed: 0.0
perclos: 0.0            frame_luminance: 142.3    lighting_mode: NORMAL
```

**19:03:20** — calibração concluída, motorista descansado dirigindo em avenida com boa luz:
```
score: 8.2              state: NORMAL
eye_openness: 0.91      blink_rate: 18.5      is_yawning: false
is_face_detected: true  alert_active: false
speed: 12.4
perclos: 0.03           perclos_contribution: 1.95     blink_contribution: 6.25    yawn_contribution: 0.0
frame_luminance: 128.7  lighting_mode: NORMAL
```

**19:32:10** — 30 minutos depois, sol se pondo, `LightingMonitor` migrou para LOW_LIGHT:
```
score: 22.4             state: NORMAL
eye_openness: 0.76      blink_rate: 22.1      is_yawning: false
speed: 15.2
perclos: 0.11           perclos_contribution: 7.15     blink_contribution: 15.25   yawn_contribution: 0.0
ambient_light_lux: 32.4 frame_luminance: 74.2          lighting_mode: LOW_LIGHT
```

**19:38:44** — motorista começa a fechar os olhos por períodos mais longos, PERCLOS sobe, estado transiciona para WARNING:
```
score: 58.7             state: WARNING
eye_openness: 0.42      blink_rate: 12.3      is_yawning: false
perclos: 0.47           perclos_contribution: 30.55    blink_contribution: 22.6    yawn_contribution: 0.0
frame_luminance: 68.4   lighting_mode: LOW_LIGHT
```

**19:39:12** — bocejo detectado, alerta dispara:
```
score: 74.2             state: FATIGUED
eye_openness: 0.35      blink_rate: 10.8      is_yawning: true      alert_active: true
perclos: 0.55           perclos_contribution: 35.75    blink_contribution: 25.9    yawn_contribution: 25.0
lighting_mode: LOW_LIGHT
```

**19:41:00** — motorista faz uma pausa mental, olhos voltam a abrir, score cai:
```
score: 28.4             state: WARNING
eye_openness: 0.85      blink_rate: 16.7      is_yawning: false     alert_active: false
perclos: 0.09
lighting_mode: LOW_LIGHT
```

### 4. Encerramento — tabela `sessions`

Quando João toca em **Parar** (ou o serviço é destruído), o `TelemetryWriter` fecha o CSV e escreve um `session_summary.json` na pasta local. Depois, o `SyncWorker` faz o `upsert` de uma linha em `sessions`.

**Esquema completo da tabela `sessions`**:

| Coluna Supabase | Tipo | Descrição |
|---|---|---|
| `id` | uuid | UUID único da sessão (usado como FK em `telemetry_records`) |
| `user_id` | uuid | Referência ao `profiles.id` do motorista |
| `start_time` | bigint | Início da sessão (epoch ms) |
| `end_time` | bigint | Encerramento (epoch ms) |
| `duration_ms` | bigint | `end_time - start_time` |
| `total_alerts` | int4 | Contagem de registros onde `alert_active=true` |
| `dominant_state` | text | Estado mais frequente durante a sessão |
| `average_score` | real | Média aritmética de todos os `score` da sessão |
| `peak_score` | real | Score máximo atingido |

**Linha resultante da viagem de João:**

```
id: 7a1c8f9d-a3e2-4b71-8c5a-1e2f3d4a5b6c
user_id: 3f2a8b4c-...
start_time: 1740170400000        (19:00:00)
end_time:   1740173100000        (19:45:00)
duration_ms: 2700000             (45 min)
total_alerts: 3
dominant_state: NORMAL
average_score: 21.4
peak_score: 74.2
```

Com esse par de tabelas relacional, você consegue:
- Listar sessões por motorista, ordenar por data ou peak_score
- Traçar a evolução do score minuto a minuto dentro de uma sessão específica
- Correlacionar `lighting_mode` com falsos alertas ou queda de precisão
- Calcular métricas agregadas (média de piscadas por motorista, sessões com >5 alertas, etc.)
- Reconstruir o trajeto via `latitude`/`longitude` das linhas de telemetria

### 5. Marcação local

Depois do upload bem-sucedido, o `SyncRepository` grava um arquivo vazio `.synced` dentro de `filesDir/sessions/{uuid}/`. Isso garante que o worker não re-envie a mesma sessão em execuções futuras.

## Permissões

Solicitadas em runtime (`SetupScreen`):

- **CAMERA** — obrigatória para detecção facial. O app não inicia sem.
- **ACCESS_FINE_LOCATION** / **ACCESS_COARSE_LOCATION** — opcionais; a telemetria de GPS ajuda na análise por região/velocidade. O app funciona sem, os campos `latitude`/`longitude`/`speed` ficam nulos.
- **POST_NOTIFICATIONS** (Android 13+) — necessária para o ícone persistente do foreground service.

Declaradas no manifest e concedidas automaticamente pelo sistema:

- `INTERNET` — para o Supabase.
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CAMERA` + `FOREGROUND_SERVICE_LOCATION`.
- `WAKE_LOCK` — para manter o CPU acordado durante o monitoramento (teto de 2 h por segurança).

## Testes

Testes unitários em JVM (sem device):

- **`FatigueScorerTest`** — 54 testes cobrindo o algoritmo: cálculo de PERCLOS, detecção de piscadas, gatilho de bocejo, transições da máquina de estados com histerese, calibração (gate + tolerância + hard cap), integridade entre sessões consecutivas, olhar-para-o-lado não inflando PERCLOS, PERCLOS sozinho não promovendo a FATIGUED, seeding pós-calibração, cinco safety nets de microsono (pre-calibração com limiar 0.20, disparo através de NO_FACE, preservação de buffers na recuperação, arm no primeiro frame raw-closed sobrevivendo ao dropout do MediaPipe, e single-frame noise não sobrevivendo em NO_FACE), e o detector de closure parcial (WARNING em 5 s de olhos abaixo de 0.45, FATIGUED em 8 s).
- **`LightingMonitorTest`** — 11 testes cobrindo a FSM de iluminação: início em NORMAL, histerese, dwell assimétrico (2 s escurecer / 3 s clarear), túneis breves não flipam o modo, funcionamento sem sensor de lux.
- **`FaceAnalyzerLightingModeTest`** — 6 testes das funções puras `shouldApplyClahe(mode)` e `gammaFor(mode)`.
- **`LuminanceCalcTest`** — 6 testes do cálculo de luminância Y (buffers uniformes, mascaramento de byte sem sinal, `rowStride` com padding).
- **`TelemetryWriterTest`** — 3 testes: sessões consecutivas, guard-rail de sessão órfã, escrita/parada de sessão única.
- **`SessionRepositoryTest`** — 2 testes: ordenação por `startTime` desc, resolução de path.

Componentes cobertos apenas por smoke testing em device: `MonitoringService`, `CameraManager`, `FaceAnalyzer.enhance` (pipeline OpenCV end-to-end), ViewModels, `SyncRepository`, `SyncWorker`, `AuthRepository`.

## Estrutura do projeto

```
app/src/main/java/com/vigilia/app/
├── MainActivity.kt              # NavHost + deep link handling
├── service/                     # MonitoringService, ServiceController, SyncWorker
├── camera/                      # CameraManager (Camera2Interop), FaceAnalyzer (MediaPipe + OpenCV)
├── lighting/                    # LightingMonitor (FSM de iluminação)
├── domain/
│   ├── model/                   # FatigueMetrics, FatigueAssessment, SessionSummary, TelemetryRecord
│   └── scoring/                 # FatigueScorer (algoritmo + FSM de fadiga)
├── data/
│   ├── telemetry/               # TelemetryWriter (CSV/JSON local)
│   ├── repository/              # SessionRepository, AuthRepository, SyncRepository
│   └── remote/                  # SupabaseClient + DTOs (ProfileDto, SessionSummaryDto, TelemetryRecordDto)
└── ui/
    ├── navigation/              # VigiliaNavGraph
    ├── auth/                    # AuthScreen, ForgotPasswordScreen, ResetPasswordScreen + ViewModel
    ├── setup/                   # SetupScreen + SetupViewModel
    ├── monitoring/              # MonitoringScreen + MonitoringViewModel
    ├── history/                 # HistoryScreen + HistoryViewModel + SessionDetailScreen + SessionDetailViewModel
    └── theme/                   # Cores e tipografia Material 3
```

## Limitações conhecidas

- **UI de gravação de vídeo** existe visualmente no `MonitoringScreen`, mas não está conectada a nenhum recorder.
- **Seleção de motorista em cabine cheia** — `FaceLandmarker` detecta até 4 rostos e `FaceAnalyzer.pickDriverIndex` escolhe o motorista pela área do bbox sobre 5 landmarks centrais estáveis, com bias sticky pro rosto anterior enquanto sua área for ≥70% do maior. Passageiros que se inclinam pra frente não roubam mais a atenção do algoritmo. Edge case não tratado: passageiro com rosto exatamente do mesmo tamanho e posição do motorista (raro — criança no colo, etc.).
- **`isLookingAway` não é exibido na UI** — computado no scorer, poderia virar um badge "Olhando ao lado" no `MonitoringScreen`.
- **Internacionalização** — strings da UI hard-coded em PT-BR. O `strings.xml` está praticamente vazio.
- **Cobertura de testes assimétrica** — o núcleo algorítmico (scoring, lighting) tem cobertura sólida; o pipeline de câmera e integrações com o sistema Android são cobertos apenas por smoke test.
- **Fase 1 (baixa luz)** — CLAHE continua rodando mesmo com o toggle "adaptação em baixa luz" desligado. Apenas a aplicação de Camera2Interop é gated. Contornar exige refactor separado.

## Documentação técnica adicional

Para detalhamento algorítmico (constantes, limiares, histórico de decisões de design), consulte [CLAUDE.md](CLAUDE.md).
