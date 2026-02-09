# Systemic Intelligence Engine (SIE) - The Systemic Triad

This document defines the three fundamental planes of the SIE, their responsibilities, and their interactions. It serves as the canonical reference for the system's cybernetic architecture.

## 1. Governance Plane (Definition)
**Purpose**: The domain of **Meaning**. To define, govern, and validate.

**Responsibilities**:
*   **(Semi) Automated Governance Processes**: The precise management of system regulation.
*   **Definitions**: Producing the "Constitution" of the system:
    *   Policies (Functional, Viability, Structural)
    *   Behavioral Rules & Control Logic
    *   Structure & Organization
    *   Purposes, Capacities, Functions, Modalities
*   **Requesting Proposals**: Asking the Operations Plane to propose interventions to align the system with Definitions.
*   **Validation**: continuously validating Operations Proposals against the Definitions to ensure coherence and safety.

**Dependencies**:
*   Needs **Observations** (from Observations Plane) to understand the current reality.
*   Needs **Deviation Signals** (from Observations Plane) to understand the gap between Definition and Reality.

---

## 2. Observations Plane (Description)
**Purpose**: The domain of **Truth**. To sense, measure, and interpret.
*(Ontologically distinct but systematically parallel to Governance)*

**Responsibilities**:
*   **(Semi) Automated Sensing & Interpretation**: The continuous reading of the system state.
*   **Observed States**: Delivering the "Facts" of the governed system (behaviors, structures, events, context).
*   **Deviation Signals**: Structured indications of *how* Reality differs from Definition.
*   **Interpretations**: Causal inferences, anomaly detection, trends, and situational awareness.

**Dependencies**:
*   Needs **Telemetry** (from the External/Governed System) across all modalities.
*   Needs **Definitions** (from Governance Plane) to provide semantic grounding for interpretation (turning data into meaning).
*   Needs **Operations Feedback** (from Operations Plane) to close the causal loop (did the intervention work?).

---

## 3. Operations Plane (Remediation)
**Purpose**: The domain of **Change**. To execute, coordinate, and actuate.
*(Symmetric to Governance and Observations)*

**Responsibilities**:
*   **(Semi) Automated Execution & Actuation**: The realization of intent.
*   **Operations Proposals**: Generating concrete, actionable plans to fix Deviations (realigning Reality with Definition).
*   **Operations Executions**: The actual implementation of validated proposals (configuration changes, actuation, resource shifts).
*   **Operations Feedback**: Reporting what was done and the immediate outcome.

**Dependencies**:
*   Needs **Validated Operations Proposals** (from Governance Plane) to ensure actions are legitimate.
*   Needs **Deviation Signals** (from Observations Plane) to know *what* to fix and *why*.
*   Needs **Affordances** (from the External/Governed System) to know what actions are possible.

---

## The Cybernetic Triad
This structure forms a clean, orthogonal, non-recursive, closed-loop system:

*   **Governance** → Provides **Meaning** (Definitions) & Validation.
*   **Observations** → Provides **Truth** (State/Deviations).
*   **Operations** → Provides **Change** (Proposals/Execution).

### Interaction Flow
1.  **Governance** publishes **Definitions** to Observations & Operations.
2.  **Observations** monitors State, compares to Definition, and emits **Deviation Signals**.
3.  **Governance** or **Operations** (depending on autonomy level) triggers on Signals.
4.  **Governance** requests **Operations Proposals** to fix Deviations.
5.  **Operations** generates Proposals based on System Affordances.
6.  **Governance** **Validates** Proposals against Policies.
7.  **Operations** **Executes** Validated Proposals.
8.  **Operations** sends **Feedback** to Observations.
9.  **Observations** updates State/Deviation (Closing the loop).
