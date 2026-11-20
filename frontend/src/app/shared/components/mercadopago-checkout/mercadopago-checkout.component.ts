import { Component, OnDestroy, computed, inject, input, output, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Observable, firstValueFrom } from 'rxjs';
import { MercadoPagoSdkService } from '../../../core/services/mercadopago-sdk.service';
import { mensajeError } from '../../../core/utils/errores';

/** Lo que el navegador manda al servidor: el token de MercadoPago.js (nunca la tarjeta) y los datos del pagador. */
export interface DatosCobroMp {
  token: string;
  metodo: string;
  emisor?: string | null;
  cuotas?: number;
  email: string;
  tipoDocumento?: string | null;
  numeroDocumento?: string | null;
}

/** Respuesta del servidor tras cobrar: APROBADO, PENDIENTE (Mercado Pago lo revisa) o RECHAZADO, con el motivo en palabras. */
export interface ResultadoMp {
  estado: 'APROBADO' | 'PENDIENTE' | 'RECHAZADO';
  mensaje: string;
  operacion: string | null;
  /** Pago confirmado y número de su comprobante (voucher), cuando se aprobó. */
  pagoId?: number | null;
  comprobante?: string | null;
}

let secuencia = 0;

/**
 * Pago con Mercado Pago dentro de nuestra página: pestaña Yape (celular + código de aprobación) y pestaña Tarjeta (formulario oficial de
 * Mercado Pago, Card Payment Brick). No hay redirecciones: el resultado se ve al instante. El monto lo fija el servidor.
 */
@Component({
  selector: 'app-mercadopago-checkout',
  imports: [DecimalPipe, FormsModule],
  template: `
<div class="mpc">
  <div class="mpc-tabs" role="tablist">
    <button type="button" role="tab" class="mpc-tab" [class.activa]="medio() === 'YAPE'" [attr.aria-selected]="medio() === 'YAPE'"
            [disabled]="procesando()" (click)="elegir('YAPE')">
      <span class="mpc-yape-logo">yape</span> Yape
    </button>
    <button type="button" role="tab" class="mpc-tab" [class.activa]="medio() === 'TARJETA'" [attr.aria-selected]="medio() === 'TARJETA'"
            [disabled]="procesando()" (click)="elegir('TARJETA')">
      💳 Tarjeta
    </button>
  </div>

  <div class="mpc-monto">Total a pagar: <strong>S/ {{ monto() | number: '1.2-2' }}</strong></div>

  @if (resultado(); as r) {
    <div class="mpc-aviso" [class.ok]="r.estado === 'APROBADO'" [class.warn]="r.estado === 'PENDIENTE'" [class.error]="r.estado === 'RECHAZADO'" role="status">
      <strong>{{ r.estado === 'APROBADO' ? '✅ Pago aprobado' : r.estado === 'PENDIENTE' ? '⏳ Pago en revisión' : '❌ Pago no aprobado' }}</strong>
      <div>{{ r.mensaje }}</div>
      @if (r.operacion) { <small>N.° de operación Mercado Pago: {{ r.operacion }}</small> }
      @if (r.estado === 'APROBADO' && r.comprobante) { <small>Comprobante: <b>{{ r.comprobante }}</b></small> }
    </div>
    @if (r.estado === 'APROBADO' && descargarComprobante()) {
      <button type="button" class="mpc-btn-comprobante" [disabled]="descargando()" (click)="descargar(r)">
        {{ descargando() ? 'Generando comprobante…' : '📄 Descargar comprobante de pago (PDF)' }}
      </button>
    }
  }
  @if (error()) { <div class="mpc-aviso error" role="alert">{{ error() }}</div> }

  @if (!aprobado()) {
    @if (medio() === 'YAPE') {
      <div class="mpc-panel">
        <ol class="mpc-pasos">
          <li>Abre tu app <b>Yape</b>.</li>
          <li>En el menú, toca <b>«Código de aprobación»</b>.</li>
          <li>Escribe aquí tu celular y ese código de 6 dígitos.</li>
        </ol>
        <label class="mpc-campo">Celular registrado en Yape
          <input type="tel" inputmode="numeric" maxlength="9" placeholder="9XXXXXXXX" [(ngModel)]="celular" [disabled]="procesando()" autocomplete="tel-national">
        </label>
        <label class="mpc-campo">Código de aprobación
          <input type="text" inputmode="numeric" maxlength="6" placeholder="123456" [(ngModel)]="otp" [disabled]="procesando()" autocomplete="one-time-code">
        </label>
        @if (!email()) {
          <label class="mpc-campo">Correo (para tu constancia de Mercado Pago)
            <input type="email" placeholder="tucorreo@ejemplo.com" [(ngModel)]="correo" [disabled]="procesando()" autocomplete="email">
          </label>
        }
        <button type="button" class="mpc-btn-yape" [disabled]="procesando()" (click)="pagarConYape()">
          {{ procesando() ? 'Procesando pago…' : 'Pagar S/ ' + (monto() | number: '1.2-2') + ' con Yape' }}
        </button>
      </div>
    } @else {
      <div class="mpc-panel">
        @if (cargandoTarjeta()) { <p class="mpc-cargando">Cargando el formulario seguro de Mercado Pago…</p> }
        <div [id]="idTarjeta"></div>
      </div>
    }
    <p class="mpc-seguro">🔒 Pago procesado por <b>Mercado Pago</b>. El colegio no recibe los datos de tu tarjeta.</p>
    @if (conOtrosMedios()) {
      <button type="button" class="mpc-link" [disabled]="procesando()" (click)="otrosMedios.emit()">Otros medios (PagoEfectivo, banca por internet): pagar en la web de Mercado Pago</button>
    }
  }
</div>
`,
  styles: [`
.mpc { display: grid; gap: 12px; }
.mpc-tabs { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.mpc-tab { display: flex; align-items: center; justify-content: center; gap: 8px; padding: 10px 12px; border-radius: 10px;
  border: 2px solid #d0d5dd; background: #fff; font: inherit; font-weight: 600; cursor: pointer; color: #344054; }
.mpc-tab.activa { border-color: #742284; background: #f8f0fa; color: #742284; }
.mpc-tab:disabled { opacity: .6; cursor: default; }
.mpc-yape-logo { background: #742284; color: #fff; font-weight: 800; border-radius: 6px; padding: 1px 6px; font-size: .85em; letter-spacing: .5px; }
.mpc-monto { font-size: 1.05rem; }
.mpc-panel { display: grid; gap: 10px; }
.mpc-pasos { margin: 0; padding-left: 20px; color: #475467; font-size: .92rem; line-height: 1.5; }
.mpc-campo { display: grid; gap: 4px; font-size: .9rem; font-weight: 600; color: #344054; }
.mpc-campo input { padding: 10px 12px; border: 1px solid #d0d5dd; border-radius: 8px; font: inherit; font-weight: 400; }
.mpc-campo input:focus { outline: 2px solid #742284; outline-offset: 1px; border-color: #742284; }
.mpc-btn-yape { padding: 12px; border: 0; border-radius: 10px; background: #742284; color: #fff; font: inherit; font-weight: 700; cursor: pointer; }
.mpc-btn-yape:disabled { opacity: .7; cursor: default; }
.mpc-aviso { border-radius: 10px; padding: 10px 12px; display: grid; gap: 4px; font-size: .92rem; background: #f2f4f7; }
.mpc-aviso.ok { background: #ecfdf3; color: #05603a; }
.mpc-aviso.warn { background: #fffaeb; color: #93370d; }
.mpc-aviso.error { background: #fef3f2; color: #b42318; }
.mpc-seguro { margin: 0; font-size: .82rem; color: #667085; }
.mpc-cargando { margin: 0; color: #667085; }
.mpc-btn-comprobante { padding: 11px; border: 2px solid #05603a; border-radius: 10px; background: #fff; color: #05603a; font: inherit; font-weight: 700; cursor: pointer; }
.mpc-btn-comprobante:disabled { opacity: .7; cursor: default; }
.mpc-link { background: none; border: 0; padding: 0; color: #009ee3; font: inherit; font-size: .85rem; text-decoration: underline; cursor: pointer; text-align: left; }
`]
})
export class MercadoPagoCheckoutComponent implements OnDestroy {
  private readonly sdk = inject(MercadoPagoSdkService);

  readonly monto = input.required<number>();
  readonly publicKey = input.required<string>();
  /** Correo del pagador si ya se conoce (si no, se pide). */
  readonly email = input<string | null>(null);
  /** Cobra en el servidor con el token. El componente solo muestra el resultado. */
  readonly cobrar = input.required<(d: DatosCobroMp) => Observable<ResultadoMp>>();
  readonly conOtrosMedios = input(false);
  /** Descarga el comprobante (voucher) en PDF del pago aprobado. Sin ella no se muestra el botón. */
  readonly descargarComprobante = input<((r: ResultadoMp) => Observable<Blob>) | null>(null);
  /** Se emite con el pago APROBADO o PENDIENTE (el padre refresca su pantalla). */
  readonly terminado = output<ResultadoMp>();
  readonly otrosMedios = output<void>();

  protected readonly idTarjeta = `mp-tarjeta-${++secuencia}`;
  protected readonly medio = signal<'YAPE' | 'TARJETA'>('YAPE');
  protected readonly procesando = signal(false);
  protected readonly cargandoTarjeta = signal(false);
  protected readonly resultado = signal<ResultadoMp | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly aprobado = computed(() => this.resultado()?.estado === 'APROBADO' || this.resultado()?.estado === 'PENDIENTE');

  protected celular = '';
  protected otp = '';
  protected correo = '';

  private brick: { unmount: () => void } | null = null;
  protected readonly descargando = signal(false);

  protected descargar(r: ResultadoMp): void {
    const fn = this.descargarComprobante();
    if (!fn) return;
    this.descargando.set(true);
    this.error.set(null);
    fn(r).subscribe({
      next: blob => {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `Comprobante-${r.comprobante ?? r.operacion ?? 'pago'}.pdf`;
        a.click();
        setTimeout(() => URL.revokeObjectURL(url), 10000);
        this.descargando.set(false);
      },
      error: e => { this.descargando.set(false); this.error.set(mensajeError(e, 'No se pudo descargar el comprobante. Intenta de nuevo.')); }
    });
  }

  ngOnDestroy(): void {
    this.desmontarTarjeta();
  }

  protected elegir(m: 'YAPE' | 'TARJETA'): void {
    if (this.medio() === m) return;
    this.error.set(null);
    this.medio.set(m);
    if (m === 'TARJETA') setTimeout(() => this.montarTarjeta()); else this.desmontarTarjeta();
  }

  protected async pagarConYape(): Promise<void> {
    this.error.set(null);
    this.resultado.set(null);
    const celular = this.celular.replace(/\D/g, '');
    const otp = this.otp.replace(/\D/g, '');
    const email = (this.email() ?? this.correo).trim();
    if (!/^9\d{8}$/.test(celular)) { this.error.set('El celular debe tener 9 dígitos y empezar con 9.'); return; }
    if (!/^\d{6}$/.test(otp)) { this.error.set('El código de aprobación tiene 6 dígitos. Lo encuentras en el menú de tu app Yape.'); return; }
    if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) { this.error.set('Escribe un correo válido.'); return; }
    this.procesando.set(true);
    try {
      const mp = await this.sdk.instancia(this.publicKey());
      const token = await mp.yape({ otp, phoneNumber: celular }).create();
      await this.enviar({ token: token.id, metodo: 'yape', cuotas: 1, email });
    } catch (e) {
      this.error.set(this.textoError(e, 'No se pudo validar Yape. Revisa el celular y genera un código de aprobación nuevo.'));
    } finally {
      this.otp = '';   // el código de aprobación es de un solo uso
      this.procesando.set(false);
    }
  }

  private async montarTarjeta(): Promise<void> {
    if (this.brick || this.medio() !== 'TARJETA') return;
    this.cargandoTarjeta.set(true);
    try {
      const mp = await this.sdk.instancia(this.publicKey());
      const email = this.email();
      this.brick = await mp.bricks().create('cardPayment', this.idTarjeta, {
        initialization: { amount: this.monto(), ...(email ? { payer: { email } } : {}) },
        customization: { paymentMethods: { maxInstallments: 1 }, visual: { style: { theme: 'default' } } },
        callbacks: {
          onReady: () => this.cargandoTarjeta.set(false),
          onError: (err: unknown) => { console.warn('Mercado Pago (tarjeta):', err); this.cargandoTarjeta.set(false); },
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
          onSubmit: async (f: any) => {
            this.error.set(null);
            this.resultado.set(null);
            this.procesando.set(true);
            try {
              await this.enviar({
                token: f.token, metodo: f.payment_method_id, emisor: f.issuer_id ? String(f.issuer_id) : null,
                cuotas: f.installments ?? 1, email: f.payer?.email ?? email ?? '',
                tipoDocumento: f.payer?.identification?.type ?? null, numeroDocumento: f.payer?.identification?.number ?? null
              });
            } catch (e) {
              this.error.set(this.textoError(e, 'No se pudo procesar la tarjeta.'));
            } finally {
              this.procesando.set(false);
              // El token es de un solo uso: si no se aprobó, se vuelve a montar el formulario para reintentar.
              if (!this.aprobado()) { this.desmontarTarjeta(); setTimeout(() => this.montarTarjeta()); }
            }
          }
        }
      });
    } catch (e) {
      this.cargandoTarjeta.set(false);
      this.error.set(this.textoError(e, 'No se pudo cargar el formulario de tarjeta de Mercado Pago.'));
    }
  }

  private desmontarTarjeta(): void {
    try { this.brick?.unmount(); } catch { /* ya desmontado */ }
    this.brick = null;
  }

  private async enviar(d: DatosCobroMp): Promise<void> {
    const r = await firstValueFrom(this.cobrar()(d));
    this.resultado.set(r);
    if (r.estado !== 'RECHAZADO') {
      this.desmontarTarjeta();
      this.terminado.emit(r);
    }
  }

  private textoError(e: unknown, porDefecto: string): string {
    // Errores del SDK (Yape/tarjeta) traen "message"; los del servidor, el formato de la API.
    const sdk = e as { message?: string; cause?: { code?: string; message?: string }[] } | null;
    if (sdk && !('status' in (sdk as object)) && sdk.message) {
      const m = sdk.message.toLowerCase();
      if (m.includes('otp') || m.includes('phone')) return 'El celular o el código de aprobación de Yape no son válidos. Genera un código nuevo en tu app.';
      return porDefecto;
    }
    return mensajeError(e, porDefecto);
  }
}
