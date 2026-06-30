"""Insert the verified physical-phone profiling results into Chapter 6."""

from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
CHAPTER = ROOT / "thesis" / "chapters" / "06-evaluation.tex"

INTRO_OLD = (
    "The results presented in this chapter were generated through "
    "\\texttt{07\\_in\\_depth\\_evaluation.ipynb} and are stored under "
    "\\texttt{ml/evaluation/results} and \\texttt{ml/evaluation/plots}. "
    "Profiling on the physical devices and validation with users are kept as "
    "separate stages, since measurements made on a desktop cannot replace "
    "those obtained on the target phone and watch."
)
INTRO_NEW = (
    "The model-evaluation results were generated through "
    "\\texttt{07\\_in\\_depth\\_evaluation.ipynb}, while the physical-phone "
    "measurements are processed through \\texttt{08\\_device\\_profiling.ipynb}. "
    "The generated tables and plots are stored under "
    "\\texttt{ml/evaluation/results} and \\texttt{ml/evaluation/plots}. "
    "Validation with users remains a separate stage because technical checks "
    "cannot show whether the explanations and feedback flow are understandable."
)

PROFILING_SECTION = r"""\section{On-device Profiling}
\label{sec:on-device-profiling}

\subsection{Profiling Protocol}
\label{subsec:profiling-protocol}

The phone-side pipeline was profiled on a Redmi Note 10 Pro, model M2101K6G, running Android 13 (build \texttt{TKQ1.221013.002}). The phone uses a Snapdragon 732G platform, an \texttt{arm64-v8a} ABI, and 6~GB of nominal RAM, of which the application reported 5569~MB. A debug build was used, the screen remained on, and the phone was connected to the computer through USB and was therefore charging during the measurements.

The inference benchmark injects synthetic 60-second sensor windows into the same processing method used for windows received from the watch. Three independent runs were performed. Each one contained 10 warm-up windows followed by 100 measured windows, giving 300 measured windows in total. The warm-up rows were saved in the raw exports, but they were excluded from all reported statistics.

The instrumentation uses \texttt{SystemClock.elapsedRealtimeNanos()} around feature extraction, normalization, TFLite inference, explanation generation, Room persistence, and immediate alert-policy evaluation. The measured complete pipeline starts after a full sensor window is available and ends after the alert policy has been evaluated. It does not include sensor acquisition on the watch, Wear Data Layer transfer, optional context enrichment, or the later synchronization of the result back to the watch.

Perfetto traces were also collected using the \texttt{MW.*} sections implemented in the application. However, the persisted monotonic-clock measurements are used for the latency table. In particular, \texttt{MW.alert} surrounds a suspending function, while Android trace sections are thread-local. Coroutine suspension or migration can therefore produce misleading individual Perfetto slice durations for that stage.

The local federated-learning path was measured separately using 20 labelled examples, equally divided between stress and non-stress. Each execution used two local epochs and a batch size of eight. Four executions were captured: the first one was a cold execution, while the following three reused initialized application state.

\subsection{Profiling Results}
\label{subsec:profiling-results}

Table~\ref{tab:device-inference-latency} presents the latency obtained from the 300 measured windows. The complete phone-side pipeline has a median of 141.18~ms, a p95 of 163.51~ms and a p99 of 182.41~ms. Even the maximum observed value, 241.47~ms, remains below the 10-second limit defined by NFR1 after a complete window becomes available.

\begin{table}[!htbp]
\centering
\footnotesize
\setlength{\tabcolsep}{4pt}
\caption{Physical-phone latency over 300 measured sensor windows}
\label{tab:device-inference-latency}
\begin{tabular}{lrrrrr}
\toprule
\textbf{Stage} & \textbf{Mean} & \textbf{Median} & \textbf{P95} & \textbf{P99} & \textbf{Maximum} \\
\midrule
Feature extraction & 3.77 ms & 3.54 ms & 6.72 ms & 12.91 ms & 16.37 ms \\
Normalization & 0.23 ms & 0.18 ms & 0.57 ms & 0.69 ms & 4.85 ms \\
TFLite inference & 5.54 ms & 5.16 ms & 9.26 ms & 15.84 ms & 23.78 ms \\
XAI generation & 4.83 ms & 4.69 ms & 8.37 ms & 12.63 ms & 19.04 ms \\
Room persistence & 20.15 ms & 19.88 ms & 30.44 ms & 41.17 ms & 47.96 ms \\
Alert-policy evaluation & 104.73 ms & 105.88 ms & 124.49 ms & 131.05 ms & 179.82 ms \\
Complete pipeline & 139.26 ms & 141.18 ms & 163.51 ms & 182.41 ms & 241.47 ms \\
\bottomrule
\end{tabular}
\end{table}

Figure~\ref{fig:device-inference-latency} shows the same result while keeping the shorter computational stages visible. The alert-policy stage is the largest component. This stage does more than compare one probability with a threshold: it reads recent stress values from Room, computes the rolling mean, loads the user's threshold, cooldown and quiet-hour settings, and may also load the highest-ranked explanation before displaying a notification.

\begin{figure}[!htbp]
\centering
\includegraphics[width=0.98\textwidth]{pics/device_inference_latency.png}
\caption{Phone-side latency after a complete sensor window becomes available}
\label{fig:device-inference-latency}
\end{figure}

The complete-pipeline means for the three runs were 143.11~ms, 136.97~ms and 137.68~ms. Figure~\ref{fig:device-inference-stability} shows that their distributions are similar, although the second run contains the largest isolated value. This makes the percentile values more useful than reporting only one average.

\begin{figure}[!htbp]
\centering
\includegraphics[width=0.88\textwidth]{pics/device_inference_run_stability.png}
\caption{Complete-pipeline distribution and mean stage latency across the three runs}
\label{fig:device-inference-stability}
\end{figure}

The federated-learning results are summarized in Table~\ref{tab:device-fl-latency}. The cold execution includes the first initialization of the model and Flex delegate. For the three warm executions, local processing takes 122.89~ms on average, while the measured upload adds another 38.67~ms. Since upload was recorded as a separate trace section, the last column is calculated by adding it to the local wall time.

\begin{table}[!htbp]
\centering
\scriptsize
\setlength{\tabcolsep}{3.2pt}
\caption{Physical-phone federated-learning latency}
\label{tab:device-fl-latency}
\begin{tabular}{lrrrrrrr}
\toprule
\textbf{Execution} & \textbf{Load} & \textbf{Restore} & \textbf{Train} & \textbf{Save} & \textbf{Upload} & \textbf{Local total} & \textbf{With upload} \\
\midrule
Cold & 134.89 ms & 23.45 ms & 98.41 ms & 0.20 ms & 125.32 ms & 257.75 ms & 383.07 ms \\
Warm mean & 37.59 ms & 6.92 ms & 77.79 ms & 0.08 ms & 38.67 ms & 122.89 ms & 161.56 ms \\
\bottomrule
\end{tabular}
\end{table}

\begin{figure}[!htbp]
\centering
\includegraphics[width=0.90\textwidth]{pics/device_fl_phase_latency.png}
\caption{Cold and warm federated-learning phase latency on the physical phone}
\label{fig:device-fl-latency}
\end{figure}

Perfetto reports 18,172.70~ms of application CPU time during a 15,217.05~ms inference-benchmark trace. This corresponds to 119.42\% of one fully occupied core, or approximately 1.19 cores on average. The value can exceed 100\% because CPU time is summed across application threads. It should not be interpreted as 119.42\% of the complete octa-core processor. The benchmark deliberately processes synthetic windows one after another, so this value represents a stress-test workload rather than normal background use.

For the cold FL interval, Perfetto reports 367.61~ms of CPU time over 257.75~ms of local wall time, equivalent to approximately 1.43 occupied cores. The available memory counters show an average Java heap size of 12.25~MiB, with a peak of 12.35~MiB, and an HWUI memory counter of 21.11~MiB. These counters describe only the recorded heap and rendering categories and must not be treated as the total process resident memory.

Battery consumption could not be evaluated from this session because the phone was charging over USB and the screen remained on. Consequently, NFR2 is only validated with respect to the implemented scheduling constraints; the energy cost of continuous watch sensing and a full local-training cycle remains future work. The experiment also does not measure watch-to-phone transmission latency or long-running behavior with real sensor windows.

"""

REQUIREMENTS_SECTION = r"""\section{Requirements Validation}
\label{sec:requirements-validation}

The requirements defined in Chapter~\ref{ch:requirements} are connected to the available evaluation evidence in Tables~\ref{tab:functional-requirements-validation} and~\ref{tab:nonfunctional-requirements-validation}. A requirement is marked as partially evaluated when its implementation is present, but validation still depends on battery measurements or on the user study.

\begin{table}[!htbp]
\footnotesize
\centering
\caption{Functional-requirement validation}
\label{tab:functional-requirements-validation}
\begin{tabular}{p{1.0cm}p{9.3cm}p{3.1cm}}
\toprule
\textbf{ID} & \textbf{Evidence} & \textbf{Status} \\
\midrule
R1 & Wearable payload and buffer tests cover the multimodal sensor contract and missing-sensor flags. & Satisfied \\
R2 & Inference and local training run on the phone; payload and schema checks find no raw biometric upload fields. & Satisfied \\
R3 & Saliency output and sensor-group mapping are tested, but understanding by users remains to be evaluated. & Partial \\
R4 & Context events are implemented and locally stored, but were not a main quantitative evaluation target. & Partial \\
R5 & Feedback and journal storage are implemented; simulated feedback is evaluated, but no task-based user study is complete. & Partial \\
R6 & Network-boundary and backend-schema checks support local-only storage of sensitive records. & Satisfied \\
R7 & Threshold behavior and alert logic are tested; the physical-phone decision/alert path remains below the configured latency target. & Satisfied \\
R8 & Weight extraction, local training, upload/download and sample-weighted aggregation are implemented and evaluated. & Satisfied \\
\bottomrule
\end{tabular}
\end{table}

\begin{table}[!htbp]
\footnotesize
\centering
\caption{Non-functional-requirement validation}
\label{tab:nonfunctional-requirements-validation}
\begin{tabular}{p{1.2cm}p{9.1cm}p{3.1cm}}
\toprule
\textbf{ID} & \textbf{Evidence} & \textbf{Status} \\
\midrule
NFR1 & On the tested phone, the complete measured path has 141.18~ms median latency, 182.41~ms p99 and 241.47~ms maximum latency, below 10 seconds. & Satisfied \\
NFR2 & WorkManager and battery checks are implemented, but battery drain could not be measured while the phone was charging over USB. & Partial \\
NFR3 & Missing-sensor and partial-payload tests show graceful handling of unavailable modalities. & Satisfied \\
NFR4 & Offline mode and local inference do not require backend availability. & Satisfied \\
NFR5 & Authentication, role and k-anonymity rejection tests behave as expected. & Satisfied \\
NFR6 & The pipeline, notebooks, raw exports, generated CSV files and plots make the reported experiments reproducible. & Satisfied \\
NFR7 & The interface groups technical features into four sensor categories, but user understanding remains pending. & Pending study \\
\bottomrule
\end{tabular}
\end{table}

"""


def replace_section(text: str, start_marker: str, end_marker: str, replacement: str) -> str:
    start = text.index(start_marker)
    end = text.index(end_marker, start)
    return text[:start] + replacement + text[end:]


text = CHAPTER.read_text(encoding="utf-8")
if INTRO_OLD in text:
    text = text.replace(INTRO_OLD, INTRO_NEW, 1)

runtime_row = (
    "Runtime performance & Does the phone produce the decision and alert within "
    "the required time, and what resources are observed? & Physical-phone latency "
    "records, FL phase timings and Perfetto CPU/memory counters. \\\\\n"
)
deployment_row = (
    "Deployment fidelity & Does the exported model preserve the Python model's "
    "behavior? & Keras/TFLite parity, scaler parity and artifact sizes. \\\\\n"
)
if "Runtime performance &" not in text:
    text = text.replace(deployment_row, deployment_row + runtime_row, 1)

text = replace_section(
    text,
    "\\section{On-device Profiling}",
    "\\section{User-based Validation}",
    PROFILING_SECTION,
)
text = replace_section(
    text,
    "\\section{Requirements Validation}",
    "\\section{Discussion and Limitations}",
    REQUIREMENTS_SECTION,
)

old_discussion = (
    "Deployment parity is strong, as the TFLite exports preserve the Keras "
    "decisions and the scaler values match exactly. However, the most important "
    "remaining evaluation work cannot be completed only through the notebook. "
    "Physical-device profiling is needed for latency, memory and battery claims, "
    "while a small task-based study is needed to validate whether users understand "
    "the explanations, feedback flow and privacy controls."
)
new_discussion = (
    "Deployment parity is strong, as the TFLite exports preserve the Keras "
    "decisions and the scaler values match exactly. Physical-phone profiling also "
    "shows that the measured decision and alert path remains well below the "
    "10-second requirement. However, the charging and screen-on setup does not "
    "support a battery-life claim, and the synthetic benchmark does not include "
    "watch sensing or Wear Data Layer transfer. A small task-based study is still "
    "needed to validate whether users understand the explanations, feedback flow "
    "and privacy controls."
)
text = text.replace(old_discussion, new_discussion, 1)

CHAPTER.write_text(text, encoding="utf-8")
print(f"Updated {CHAPTER}")
