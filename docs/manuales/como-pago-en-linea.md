# Cómo pago en línea

En **Cuenta y pagos** ves tu saldo, tus cuotas, tus pagos con su factura y cada movimiento de tu cuenta.

## Paso a paso

1. Revisa tu **saldo pendiente** y la próxima cuota.
2. Escribe el monto (por defecto, todo tu saldo) y pulsa **Pagar en línea**.
3. Se abre la pasarela de pagos. Completa el pago allí.
4. Al volver, verás el pago **confirmado** y podrás descargar tu **factura electrónica**.

> En la versión de demostración la pasarela es **simulada**: no pide datos de tarjeta y te deja elegir si el pago se aprueba, se rechaza o llega tarde.

## Un pago nunca se cobra dos veces {#rn-24}

Si haces doble clic, si se corta tu conexión o si la pasarela avisa dos veces, el pago se registra **una sola vez**. Cada intento lleva una clave única y la base de datos impide duplicados.

## A qué se aplica mi pago {#rn-25}

El pago se aplica en el orden que define la universidad: primero los **intereses por mora** y luego la **cuota más antigua**. Así nunca quedan intereses creciendo sobre una cuota que ya pagaste en parte.

## Intereses por mora {#rn-26}

Si una cuota vence sin pagarse, se calcula interés con la **regla vigente al vencimiento**, no con la de hoy. Si la universidad cambia la tasa, tus cuotas ya vencidas mantienen la regla de su fecha.

## Desbloqueo inmediato {#rn-27}

Si una deuda te bloqueaba la matrícula, en cuanto el pago se confirma el bloqueo desaparece. No hay que esperar a que nadie lo levante.

## Anulaciones {#rn-28}

Un pago nunca se borra. Si Financiero debe anularlo (por ejemplo, por un error de caja), se emite una **nota de crédito** con motivo y el movimiento se compensa con un reverso.

## Mi estado de cuenta siempre cuadra {#rn-29}

Cargos + intereses − descuentos − pagos − notas de crédito ± reversos = saldo. La pantalla verifica esta cuenta y te avisa si algo no coincide.

## Si un pago queda pendiente

Si la pasarela no avisa a tiempo, UNISFERA consulta el estado del pago por su cuenta y lo resuelve en unos minutos. No vuelvas a pagar: revisa *Pagos y facturas*.
