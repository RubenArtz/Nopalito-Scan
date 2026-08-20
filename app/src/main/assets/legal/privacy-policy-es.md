# Política de privacidad de Nopalito Scan

**Última actualización:** 19/08/2026

## 1. Responsable y contacto

Esta Política explica cómo **Ruben Matias**, responsable de **Nopalito Scan**, trata los datos personales. Contacto de
privacidad y soporte: **contact@nopalitoscan.org**. Domicilio o país del responsable: **México**.

## 2. Qué datos tratamos

Nopalito Scan está diseñado para procesar la mayor parte de la información localmente en el dispositivo. Según las
funciones que utilice el Usuario, podemos tratar:

- **Datos de cuenta:** correo electrónico, identificador de usuario, datos de autenticación y metadatos necesarios para
  mantener la sesión.
- **Contenido del Usuario:** fotografías, escaneos, PDF, imágenes, textos extraídos y códigos QR que el Usuario
  seleccione o capture.
- **Archivos temporales:** archivos enviados al servicio en la nube para ejecutar una conversión o procesamiento
  solicitado.
- **Archivos almacenados:** documentos y metadatos mínimos —por ejemplo, nombre, tamaño, fecha y tipo— cuando el Usuario
  inicia sesión y autoriza guardarlos en la nube.
- **Datos técnicos y registros:** dirección IP (almacenada en texto plano o como hash unidireccional según la
  configuración), país aproximado derivado de la IP, agente de usuario, versión de la Aplicación y plataforma,
  identificador de la solicitud, endpoint, método, estado y duración de cada petición, correo e identificador del
  usuario cuando ha iniciado sesión, token de notificaciones push cuando estén habilitadas, y eventos de negocio
  (inicio de sesión, subida, descarga, borrado, conversión y acciones administrativas).
- **Permisos del dispositivo:** cámara y almacenamiento o selector de archivos, únicamente cuando sean necesarios para
  la función elegida. Nopalito Scan no debe declarar permisos que realmente no solicite.

No pretendemos recopilar categorías especiales de datos. Sin embargo, los documentos que el Usuario escanee pueden
contener información personal, financiera, de salud, identificaciones oficiales u otra información sensible. El Usuario
decide qué documentos envía o guarda y debe evitar cargar información que no sea necesaria.

## 3. Cómo usamos los datos

Usamos los datos para:

- capturar, convertir, mejorar, generar, previsualizar, descargar y entregar documentos;
- guardar, sincronizar y eliminar archivos cuando el Usuario lo solicite y autorice;
- autenticar cuentas, mantener sesiones y prestar soporte;
- detectar abuso, proteger la seguridad, investigar incidentes y mantener la disponibilidad; y
- cumplir obligaciones legales y responder a solicitudes válidas de autoridades.

No vendemos datos personales ni usamos el Contenido del Usuario para publicidad. No usamos los documentos para entrenar
modelos ni para fines distintos de prestar la función solicitada, salvo que el Usuario otorgue una autorización separada
y explícita o la ley lo permita de forma obligatoria.

## 4. Qué ocurre cuando se utiliza la nube

El procesamiento local no requiere enviar el documento al Operador. Si el Usuario selecciona una función de conversión o
procesamiento en la nube, el archivo se transmite mediante **HTTPS/TLS** a la infraestructura del Operador o a sus
proveedores técnicos para ejecutar esa solicitud.

El archivo subido para procesamiento temporal se elimina al terminar la operación: en las conversiones de vista
previa, el archivo y todos los artefactos intermedios se borran en cuanto se entrega el resultado; en las
conversiones por lotes, los archivos subidos y los directorios de trabajo se eliminan al finalizar el trabajo. Los
restos por fallos o interrupciones se limpian automáticamente en un máximo de 1 hora (archivos temporales de subida)
y 24 horas (directorios de trabajo de conversión). Si el Usuario no ha iniciado sesión, los resultados de conversión
se conservan bajo una identidad técnica anónima y se eliminan automáticamente a los 90 días. Los códigos QR generados
por el Usuario se publican en URLs públicas e inmutables y no se eliminan automáticamente.

Si el Usuario inicia sesión y autoriza guardar el resultado, el documento se conserva en su cuenta hasta que lo elimine
o solicite la eliminación de la cuenta. El Usuario puede verlo en vista previa, descargarlo y eliminarlo desde la
Aplicación.

## 5. Proveedores y transferencias

Podemos compartir datos con proveedores que alojan servidores, almacenan archivos, entregan correo, gestionan
autenticación, monitorizan errores o protegen la infraestructura. Estos proveedores solo deben tratar los datos
siguiendo instrucciones del Operador y para prestar esos servicios. En particular, los proveedores pueden incluir:
alojamiento y base de datos (MySQL), almacenamiento de objetos local o compatible con S3 (por ejemplo, Amazon S3,
Cloudflare R2, MinIO, Backblaze B2 o Wasabi según la configuración), envío de correo (SMTP), red de entrega y
protección (Cloudflare), notificaciones push (Firebase Cloud Messaging) y geolocalización de IPs (ip-api.com, con
caché local a nivel de país).

No compartimos documentos con anunciantes ni con terceros para sus propios fines comerciales. Podemos divulgar
información si es necesario para cumplir una obligación legal, proteger derechos y seguridad, investigar fraude o
responder a una emergencia.

## 6. Conservación y eliminación

Conservamos los datos de cuenta mientras exista la cuenta o mientras sean necesarios para prestar el servicio. Los
archivos guardados permanecen hasta que el Usuario los elimine o solicite eliminar la cuenta.

Los archivos de procesamiento temporal se eliminan al completar la operación; los restos por fallos o interrupciones
se limpian automáticamente en un máximo de 1 hora (archivos temporales de subida) y 24 horas (directorios de trabajo
de conversión). Los archivos enviados a la papelera se eliminan definitivamente de forma automática a los **30 días**.
Los registros de actividad y seguridad (auditoría) se conservan durante **90 días**; los trabajos de conversión,
durante **90 días**; los datos de sesión y tokens de refresco se purgan a los **30 días**; los registros de aplicación
se conservan en archivos rotatorios (14–30 archivos de 10 MB); y la geolocalización de IPs se cachea durante **30
días**.

## 7. Derechos del Usuario

Según la legislación aplicable, el Usuario puede solicitar acceso, corrección, portabilidad, eliminación, limitación u
oposición al tratamiento, y retirar un consentimiento cuando el tratamiento se base en él. También puede reclamar ante
la autoridad de protección de datos competente.

Para ejercer derechos, escriba a **contact@nopalitoscan.org** desde la dirección asociada a la cuenta e indique qué
solicita. Responderemos dentro del plazo legal aplicable y podremos pedir información razonable para verificar la
identidad.

## 8. Eliminación de cuenta

La eliminación debe poder iniciarse desde **contact@nopalitoscan.org**, sin exigir reinstalar la Aplicación. Al
completar la solicitud, eliminaremos la cuenta, los archivos guardados y los datos asociados, excepto los datos que
debamos conservar por una obligación legal o por seguridad; esos datos se limitarán, protegerán y eliminarán cuando
termine el motivo de conservación.

Plazo objetivo de finalización: **2 semanas**. La eliminación de la Aplicación del dispositivo no elimina
automáticamente una cuenta ni los datos almacenados en la nube.

## 9. Seguridad

Aplicamos medidas razonables de seguridad, como cifrado en tránsito, controles de acceso, separación de cuentas, gestión
de credenciales, registros de seguridad y minimización de datos. El inicio de sesión se realiza en dos pasos (contraseña
y un código de un solo uso enviado por correo), los tokens de acceso son de corta duración, las sesiones pueden
revocarse, los intentos se limitan por frecuencia, las contraseñas se almacenan como resúmenes unidireccionales y los
registros filtran los valores sensibles. El contenido se transmite cifrado, pero no se cifra en reposo en nuestra
infraestructura; evita guardar documentos que requieran un nivel de protección superior. Ningún sistema conectado a
internet es completamente seguro; el Usuario también debe proteger su dispositivo y sus credenciales.

## 10. Menores

Nopalito Scan no está dirigido a menores de **8 años**. No recopilamos deliberadamente datos de menores sin la base
legal o autorización exigida. Si crees que un menor nos proporcionó datos, contacta con **contact@nopalitoscan.org**
para solicitar su revisión y eliminación.

## 11. Cambios a esta Política

Podemos actualizar esta Política para reflejar cambios en la Aplicación, el backend, los proveedores o la legislación.
Publicaremos la versión vigente dentro de Nopalito Scan y cambiaremos la fecha de actualización. Si un cambio requiere
consentimiento, lo solicitaremos antes de tratar los datos para el nuevo propósito.

## 12. Aceptación

Antes de habilitar funciones que requieran aceptación, Nopalito Scan mostrará un aviso claro con enlaces a esta Política
y a los Términos. El Usuario deberá realizar una acción afirmativa —marcar la casilla circular y pulsar “Aceptar”—;
cerrar el aviso, tocar fuera de él o pulsar “Atrás” no se considerará aceptación.
