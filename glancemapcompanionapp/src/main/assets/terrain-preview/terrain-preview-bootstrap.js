// This stays ES5 so an old WebView can report a script or graphics failure.
(function () {
  function unsupported() { document.title = 'GlanceMap terrain unsupported'; }
  window.addEventListener('error', function (event) {
    if (event.target === window || (event.target && event.target.tagName === 'SCRIPT')) unsupported();
  }, true);
  window.addEventListener('unhandledrejection', function () {
    document.title = 'GlanceMap terrain error';
  });
})();
