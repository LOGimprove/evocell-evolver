<img width="1499" height="847" alt="image" src="https://github.com/user-attachments/assets/cda907e4-4865-48ad-aeb6-b292d91f8694" />



# evocell_evolver (Alpha)

A 15-pane evolutionary laboratory designed to evolve complex Cellular Automata rules using Tree-Based Genetic Programming (GP) with native Golly export support.

⚠️ **Note on Language:** The user interface of this application is in Russian, but the underlying core logic, Abstract Syntax Tree (AST) formulas, and syntax nodes are fully in English (`IF`, `GET_NEIGHBOR`, logical gates, etc.).

<p align="center">
  <img src="preview.png" alt="evocell_evolver 15-pane interface" width="800">
</p>

## 🚀 Key Features
* 🧠 **Rich AST Genetic Programming:** Evolve complex, nested rules using a versatile vocabulary of **15 to 20 distinct functional nodes** (conditionals, math, logic) instead of flat transition tables.
* 💾 **Native Golly Export:** Export your evolved rules directly into Golly's `@TREE` format (`.rule` files) to run them in the world's leading simulator.
* ⚡ **Performance:** Maintains around 30 FPS during active 15-pane parallel CPU simulation and mutation steps.

## ⚠️ Important Warning for Golly Users
To prevent memory overflow and application freezes when loading heavy custom rules into Golly, follow this exact sequence:
1. Open **Golly** and select any **standard built-in rule** (e.g., Conway's `Life`).
2. Press **Start/Run** for a few generations to properly initialize Golly's internal memory manager.
3. Only after that, change the rules to your new **custom exported rule** (`A_pulki`, etc.).

## 💻 How to Run
Make sure you have **Java 17 or higher** installed. Download the portable JAR file from the **Releases** section and run it:

```bash
java -jar EvoCell_Laboratory_2.0.jar
```

## 🛠️ AST Engine Specification (15-20 Supported Nodes)
The evolutionary core builds rules using an Abstract Syntax Tree (AST) containing a robust set of 15–20 syntax and operations nodes:

* **Spatial Sensors:** `GET_NEIGHBOR("Direction")` (e.g., "South-East"), `SET_NEXT_STATE()`, and geometric helpers like `IS_LINE()`.
* **Conditionals:** Full execution path routing using `IF`, `THEN`, and `ELSE`.
* **Logical Gates:** Advanced bitwise and logical analysis via `AND`, `OR`, `XOR`, and `XNOR`.
* **Math & Relations:** Standard arithmetic operators (`+`, `-`, `*`, `/`) and comparison nodes (`>`, `<`, `==`, `>=`, `<=`) allowing the engine to calculate density gradients, weights, and complex physics-like ballistics.
