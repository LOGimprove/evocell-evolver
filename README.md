<img width="1497" height="849" alt="image" src="https://github.com/user-attachments/assets/8810d6a1-6b1c-42c5-abf6-89eb6d6953f5" />

# evocell_evolver (Alpha)

[English]

A highly optimized Tree-Based Genetic Programming engine designed to evolve complex Cellular Automata rules with native Golly integration.

⚠️ **Note on Language:** The current user interface of the application is in Russian, but the underlying core logic, formulas, and AST nodes use standard English syntax (`IF`, `COUNT_NEIGHBORS`, etc.). English UI localization is planned for future versions.

## 🚀 Key Features
* 🧠 **Custom 2-Node Leaf Engine:** Strictly structured depth-based spatial evolution (up to 7 neighbors / 4 for Von Neumann neighborhood). No heavy branching — pure performance.
* 💾 **Golly Integration:** Native export support! Seamlessly export your evolved rules directly into Golly (`.rule` / `.rle`) to run them in the world's leading simulator.

## ⚠️ Important Warning for Golly Users / Важное предупреждение

[English]
If you are planning to test your exported rules in **Golly**, please follow this strict launch sequence to prevent memory overflow and application freezes:
1. Open **Golly** and select any **standard built-in rule** (e.g., Conway's `Life`).
2. Press **Start/Run** for a few generations to properly initialize Golly's internal memory manager.
3. Only after that, switch the simulation to your new **custom exported rule**. 

[Русский]
Если вы собираетесь экспортировать правила в **Golly**, строго соблюдайте следующую последовательность запуска, чтобы компьютер не завис от переполнения памяти:
1. Откройте **Golly**, выберите любое **стандартное встроенное правило** (например, классическую «Жизнь» — `Life`).
2. Запустите симуляцию буквально на пару секунд, чтобы Golly правильно инициализировал внутренний менеджер памяти и очистил кэш.
3. И только после этого переключайтесь на своё новое **кастомное правило**.

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
