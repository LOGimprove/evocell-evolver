<img width="1499" height="847" alt="image" src="https://github.com/user-attachments/assets/55942a29-345b-43ee-9fd1-38aadafb188c" />

# evocell_evolver (Alpha)

[English] | [Читать на русском языке](#русский-описание-проекта)

A highly optimized Tree-Based Genetic Programming engine designed to evolve complex Cellular Automata rules with native Golly integration.

⚠️ **Note on Language:** The current user interface of the application is in Russian, but the underlying core logic, formulas, and AST nodes use standard English syntax (`IF`, `COUNT_NEIGHBORS`, etc.). English UI localization is planned for future versions.

## 🚀 Key Features
* 🧠 **Custom 2-Node Leaf Engine:** Strictly structured depth-based spatial evolution (up to 7 neighbors / 4 for Von Neumann neighborhood). No heavy branching — pure performance.
* 💾 **Golly Integration:** Native export support! Seamlessly export your evolved rules directly into Golly (`.rule` / `.rle`) to run them in the world's leading simulator.

## 💻 How to Run
Make sure you have **Java 17 or higher** installed. Download the precompiled JAR file from the **Releases** section and run it:

* **Windows/macOS:** Simply double-click the `EvoCell_Laboratory_2.0.jar` file.
* **Linux / Terminal:** Run via command line:
```bash
java -jar EvoCell_Laboratory_2.0.jar
```

---

## 🇷🇺 Русский (Описание проекта)

Высокооптимизированный движок генетического программирования (Tree-Based GP) для эволюции сложных правил клеточных автоматов с поддержкой экспорта в Golly.

### 🛠️ Архитектура ядра (Core Architecture)
Проект построен на легковесной древовидной структуре без перегруженных логических узлов:
* **NodeBase / Leaf Concept:** Логика вычислений жестко привязана к пространственной глубине окрестности. Всего два типа нод обеспечивают колоссальную скорость работы алгоритма.
* **RuleChromosome:** Хромосома правила, управляющая мутациями и скрещиванием пространственных деревьев.
* **TreeGenerator:** Генератор детерминированных деревьев решений для окрестностей Фон Неймана (4 соседа) и расширенных конфигураций (до 7 соседей).
