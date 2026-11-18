# **Animator of Lince 2.1**

Lince is a lightweight animator for hybrid programs describing cyber-physical systems with discrete and continuous evolution.

Lince 2.1 extends the Lince 2.0 implementation with support for component-based concurrent hybrid programs. The extension introduces named components, qualified locations, ownership rules, dependency-aware discrete scheduling, and synchronized continuous evolution over shared physical time.

The project is implemented using the CAOS framework and currently focuses on approximate numerical execution rather than symbolic execution.

A snapshot of Lince 2.0 can be executed at:

https://lmf.di.uminho.pt/lince-2.0/

The current source code is available at:

https://github.com/arcalab/lince-2.0

The component-based extension is available in the `concurrent` branch.

The previous version of Lince, which is no longer maintained, can be found at:

http://arcatools.org/lince

## **Videos**

- [Hands-on tutorial on Lince 2.0](https://youtu.be/JFVPHVxvMjo?si=WmGAc7NDQzX5uofz), presented at the Shift2SDV European project consortium, April 2026 (11 min)
- [Talk at GAG seminars on Lince](https://youtu.be/DHOXR2AmTp8?si=FAjMzhLIjtAjSwT5), University of Aveiro, Portugal, June 2021 (46 min)

## **Publications**

- [An Adequate While-Language for Stochastic Hybrid Computation](https://jose.proenca.org/publication/ppdp-stochastic-lince-2025/), *Renato Neves, José Proença, Juliana Souza*, PPDP 2025, September 2025
- [Analyzing Many Simulations of Hybrid Programs in Lince](https://jose.proenca.org/publication/fmas-lince-2025/), *Reydel Arrieta, José Proença, Patrick Meumeu Yomsi*, FMAS@iFM 2025, November 2025
- [Formal Simulation and Visualisation of Hybrid Programs](https://jose.proenca.org/publication/fmas-lince-2024/), *Pedro Mendes, Ricardo Correia, Renato Neves, José Proença*, FMAS@iFM 2024, November 2024
- [Implementing Hybrid Semantics: From Functional to Imperative](https://jose.proenca.org/publication/goncharov-implementing-ictac-20/), *Sergey Goncharov, Renato Neves, José Proença*, ICTAC 2020, October 2020
- [An Adequate While-Language for Hybrid Computation](https://repositorio.inesctec.pt/items/6377f0aa-bea0-4d5a-b996-26ffab9e4703), *Sergey Goncharov, Renato Neves*, PPDP 2019, October 2019

## **Component-Based Concurrent Execution**

Lince 2.1 supports systems composed of multiple named hybrid programs.

For example:

```text
CTR {
    a := 2;
    wait 5;
    a := 0;
}

CAR {
    v := 0;
    p := 0;
    v' = CTR.a, p' = v for 10;
}
```

Each component owns its local state. Components may read qualified locations belonging to other components, such as `CTR.a`, but assignments and differential equations may only modify locations owned by the executing component.

Instantaneous behaviour is scheduled deterministically using source order together with dependency availability. A component whose next statement depends on unavailable state is temporarily skipped.

When several components contain active differential equations, their continuous behaviour evolves simultaneously over shared physical time until the earliest relevant execution boundary or the simulation horizon is reached.

## **CAOS**

This project uses the CAOS framework as a submodule. More information is available online:

- CAOS GitHub page: https://github.com/arcalab/CAOS
- CAOS tutorial: https://arxiv.org/abs/2304.14901
- CAOS demo video: https://youtu.be/Xcfn3zqpubw

## **Requirements**

- JVM (>= 1.8)
- sbt

Before compiling, initialize the CAOS submodule:

```bash
git submodule update --init
```

Plotly is also required by the Lince interface. If it is not already loaded by the current CAOS version, add the following line to `lib/caos/tool/index.html`:

```html
<script type="text/javascript" src="js/static/plotly.min.js"></script>
```

## **Compilation**

The project is compiled using Scala.js.

To compile and run the interface:

1. Compile the project:

```bash
sbt fastLinkJS
```

2. Open:

```text
lib/caos/tool/index.html
```

To use the component-based extension, make sure that the `concurrent` branch is selected before compiling.