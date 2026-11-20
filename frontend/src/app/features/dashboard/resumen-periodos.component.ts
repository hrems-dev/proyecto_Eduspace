import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { Observable, forkJoin, of } from 'rxjs';
import { catchError, map } from 'rxjs/operators';
import { MatriculaService } from '../../core/services/matricula.service';
import { PagoConfigService } from '../../core/services/pago-config.service';
import { PlanificacionService } from '../../core/services/planificacion.service';
import { Anio, Periodo } from '../../core/models/planificacion.model';
import { Campania, EstadoMatricula } from '../../core/models/matricula.model';
import { Morosos } from '../../core/models/pago.model';

/** Un indicador que falla no tumba el resto del resumen: queda en `null` y se muestra «—». */
const seguro = <T>(obs: Observable<T>): Observable<T | null> => obs.pipe(catchError(() => of(null)));

const ESTADOS: { estado: EstadoMatricula; texto: string }[] = [
  { estado: 'PRE_MATRICULA', texto: 'Pre-matrícula' },
  { estado: 'POR_APROBAR', texto: 'Por aprobar' },
  { estado: 'MATRICULADO', texto: 'Matriculados' },
  { estado: 'RETIRADO', texto: 'Retirados' },
  { estado: 'ANULADO', texto: 'Anulados' }
];

const TEXTO_ANIO: Record<string, string> = { ACTIVO: 'Activo', CERRADO: 'Cerrado (solo lectura)', PLANIFICADO: 'Planificado' };

interface Datos {
  periodos: Periodo[] | null;
  campanias: Campania[] | null;
  morosos: Morosos | null;
  conteos: Record<string, number | null> | null;
}

/** Inicio: elige un año (2026, 2027…) y ve en qué va: periodo, matrícula, matrículas por estado y deudores. */
@Component({
  selector: 'app-resumen-periodos',
  imports: [RouterLink, DecimalPipe],
  templateUrl: './resumen-periodos.component.html',
  styles: `
    .resumen-cab { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 14px; }
    .resumen-cab .sec-titulo { margin: 0; }
    .resumen-selector { display: flex; align-items: center; gap: 8px; font-weight: 600; color: var(--ink-2); }
    .resumen-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(190px, 1fr)); gap: 12px; }
    .resumen-dato { padding: 12px 14px; border: 1px solid var(--line); border-radius: 12px; background: var(--bg); display: flex; flex-direction: column; gap: 4px; }
    .resumen-dato strong { font-size: 1.25rem; color: var(--ink); }
    .resumen-dato span { font-size: .8rem; color: var(--ink-2); font-weight: 600; }
    .resumen-dato.alerta { border-left: 4px solid var(--danger, #c0392b); }
    .resumen-dato.ok { border-left: 4px solid var(--ok, #2e7d32); }
    .resumen-pie { margin: 12px 0 0; display: flex; gap: 14px; flex-wrap: wrap; font-size: .88rem; }
    .resumen-cargando { opacity: .6; }
  `
})
export class ResumenPeriodosComponent implements OnInit {
  private readonly plan = inject(PlanificacionService);
  private readonly matriculas = inject(MatriculaService);
  private readonly pagos = inject(PagoConfigService);

  protected readonly anios = signal<Anio[]>([]);
  protected readonly anioId = signal<number | null>(null);
  protected readonly cargando = signal(false);
  protected readonly datos = signal<Datos>({ periodos: null, campanias: null, morosos: null, conteos: null });
  protected readonly estados = ESTADOS;
  protected readonly textoAnio = (e: string) => TEXTO_ANIO[e] ?? e;

  protected readonly anio = computed(() => this.anios().find(a => a.id === this.anioId()) ?? null);
  protected readonly periodoEnCurso = computed(() => (this.datos().periodos ?? []).find(p => p.estado === 'ACTIVO') ?? null);
  protected readonly campania = computed(() => {
    const lista = this.datos().campanias ?? [];
    return lista.find(c => c.estado === 'ABIERTA') ?? lista[0] ?? null;
  });

  ngOnInit(): void {
    seguro(this.plan.anios()).subscribe(lista => {
      const todos = [...(lista ?? [])].sort((a, b) => b.nombre.localeCompare(a.nombre));
      this.anios.set(todos);
      const inicial = todos.find(a => a.estado === 'ACTIVO') ?? todos[0];
      if (inicial) this.elegir(inicial.id);
    });
  }

  /** Carga todo lo de un año; si el usuario cambia de año mientras llega la respuesta, la respuesta vieja se descarta. */
  protected elegir(id: number): void {
    this.anioId.set(id);
    this.cargando.set(true);
    forkJoin({
      periodos: seguro(this.plan.periodos(id)),
      campanias: seguro(this.matriculas.campanias(id)),
      morosos: seguro(this.pagos.morosos(undefined, undefined, id)),
      conteos: forkJoin(
        ESTADOS.map(e => seguro(this.matriculas.listar({ anioId: id, estado: e.estado, size: 1 })).pipe(map(p => p?.totalElementos ?? null)))
      )
    }).subscribe(r => {
      if (this.anioId() !== id) return;
      const conteos = Object.fromEntries(ESTADOS.map((e, i) => [e.estado, r.conteos[i]])) as Record<string, number | null>;
      this.datos.set({ periodos: r.periodos, campanias: r.campanias, morosos: r.morosos, conteos });
      this.cargando.set(false);
    });
  }

  protected soles(n: number | null | undefined): string {
    return n === null || n === undefined ? '—' : 'S/ ' + n.toLocaleString('es-PE', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  }

  protected valor(n: number | null | undefined): string {
    return n === null || n === undefined ? '—' : String(n);
  }
}
