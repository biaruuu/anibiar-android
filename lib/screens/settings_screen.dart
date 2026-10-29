import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../core/config.dart';
import '../core/nav.dart';
import '../core/theme.dart';
import '../services/auth.dart';
import '../widgets/cards.dart';
import '../widgets/glass.dart';

enum _Field { username, name, email }

class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final top = MediaQuery.of(context).padding.top;
    return ListenableBuilder(
      listenable: Auth.instance,
      builder: (context, _) {
        final a = Auth.instance;
        final rows = [
          (_Field.username, 'Username', a.handle ?? ''),
          (_Field.name, 'Display Name', a.name ?? ''),
          (_Field.email, 'Email', a.user?.email ?? ''),
        ];
        return ListView(padding: EdgeInsets.fromLTRB(16, top + 16, 16, 120), children: [
          const Text('Settings', style: TextStyle(fontSize: 26, fontWeight: FontWeight.w800, letterSpacing: -0.4)),
          const SizedBox(height: 20),
          if (a.ready && !a.signedIn)
            Panel(
              padding: const EdgeInsets.all(22),
              child: Column(children: [
                Text('Log in to manage your account.', style: TextStyle(color: C.w(0.8))),
                const SizedBox(height: 14),
                GlassButton(label: 'Log in / Sign up', kind: GlassKind.white, height: 44, onTap: () => openLogin(context)),
              ]),
            )
          else ...[
            Text('MY ACCOUNT', style: TextStyle(fontSize: 12, fontWeight: FontWeight.w800, letterSpacing: 0.8, color: C.w(0.5))),
            const SizedBox(height: 10),
            Panel(
              padding: EdgeInsets.zero,
              child: Column(children: [
                for (var i = 0; i < rows.length; i++)
                  GestureDetector(
                    behavior: HitTestBehavior.opaque,
                    onTap: () => _edit(context, rows[i].$1, rows[i].$3),
                    child: Container(
                      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
                      decoration: BoxDecoration(border: i == 0 ? null : Border(top: BorderSide(color: C.w(0.12)))),
                      child: Row(children: [
                        Expanded(
                          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                            Text(rows[i].$2, style: TextStyle(fontSize: 12.5, color: C.w(0.55))),
                            const SizedBox(height: 3),
                            Text(rows[i].$3.isEmpty ? '—' : rows[i].$3, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
                          ]),
                        ),
                        GlassButton(label: 'Edit', height: 34, fontSize: 12.5, padding: const EdgeInsets.symmetric(horizontal: 16), onTap: () => _edit(context, rows[i].$1, rows[i].$3)),
                      ]),
                    ),
                  ),
              ]),
            ),
            const SizedBox(height: 22),
            GlassButton(
              label: 'Log out',
              icon: Icons.logout_rounded,
              kind: GlassKind.danger,
              expand: true,
              height: 46,
              onTap: () async {
                await a.signOut();
                if (context.mounted) toast(context, 'Logged out');
              },
            ),
          ],
          const SizedBox(height: 28),
          Text('ABOUT', style: TextStyle(fontSize: 12, fontWeight: FontWeight.w800, letterSpacing: 0.8, color: C.w(0.5))),
          const SizedBox(height: 10),
          Panel(
            padding: EdgeInsets.zero,
            child: Column(children: [
              _Link(label: 'Open the website', onTap: () => launchUrl(Uri.parse(AppConfig.siteUrl), mode: LaunchMode.externalApplication)),
              _Link(label: 'Terms of Service', top: true, onTap: () => launchUrl(Uri.parse('${AppConfig.siteUrl}/terms'), mode: LaunchMode.externalApplication)),
              _Link(label: 'Privacy Policy', top: true, onTap: () => launchUrl(Uri.parse('${AppConfig.siteUrl}/privacy'), mode: LaunchMode.externalApplication)),
            ]),
          ),
          const SizedBox(height: 22),
          Center(child: Text('AniBiar for Android · v1.0.0', style: TextStyle(fontSize: 12, color: C.w(0.4)))),
        ]);
      },
    );
  }

  void _edit(BuildContext context, _Field f, String initial) {
    showGlassSheet<void>(context, builder: (_) => _EditSheet(field: f, initial: initial));
  }
}

class _Link extends StatelessWidget {
  const _Link({required this.label, required this.onTap, this.top = false});
  final String label;
  final VoidCallback onTap;
  final bool top;
  @override
  Widget build(BuildContext context) => GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: onTap,
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 15),
          decoration: BoxDecoration(border: top ? Border(top: BorderSide(color: C.w(0.12))) : null),
          child: Row(children: [
            Expanded(child: Text(label, style: const TextStyle(fontSize: 15.5, fontWeight: FontWeight.w600))),
            Icon(Icons.open_in_new_rounded, size: 18, color: C.w(0.5)),
          ]),
        ),
      );
}

class _EditSheet extends StatefulWidget {
  const _EditSheet({required this.field, required this.initial});
  final _Field field;
  final String initial;
  @override
  State<_EditSheet> createState() => _EditSheetState();
}

class _EditSheetState extends State<_EditSheet> {
  late final _ctrl = TextEditingController(text: widget.initial);
  bool _busy = false;
  String? _error;
  String? _done;

  static const _copy = {
    _Field.username: ('Change your username', 'Use 3 to 20 letters, numbers, dots or underscores.', 20),
    _Field.name: ('Change your display name', 'This is the name people see on your profile.', 32),
    _Field.email: ('Change your email', 'We will send a confirmation link to the new address.', 100),
  };

  Future<void> _save() async {
    final v = _ctrl.text.trim();
    final a = Auth.instance;
    setState(() {
      _error = null;
      _busy = true;
    });
    try {
      switch (widget.field) {
        case _Field.username:
          final handle = v.toLowerCase();
          if (!RegExp(r'^[a-z0-9_.]{3,20}$').hasMatch(handle)) throw Exception('Use 3 to 20 letters, numbers, dots or underscores.');
          await a.saveProfile({'handle': handle});
          if (mounted) Navigator.of(context).pop();
        case _Field.name:
          if (v.isEmpty) throw Exception('Enter a display name.');
          await a.saveProfile({'username': v});
          if (mounted) Navigator.of(context).pop();
        case _Field.email:
          if (!RegExp(r'^\S+@\S+\.\S+$').hasMatch(v)) throw Exception('Enter a valid email address.');
          if (v.toLowerCase() == (a.user?.email ?? '').toLowerCase()) throw Exception('That is already your email.');
          await a.changeEmail(v);
          setState(() => _done = 'Check your inbox. Open the confirmation link to finish changing your email.');
      }
    } catch (e) {
      if (mounted) setState(() => _error = friendlyError(e));
    }
    if (mounted) setState(() => _busy = false);
  }

  @override
  Widget build(BuildContext context) {
    final c = _copy[widget.field]!;
    return Padding(
      padding: const EdgeInsets.fromLTRB(18, 18, 18, 20),
      child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Expanded(child: Text(c.$1, style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w800))),
          GestureDetector(onTap: () => Navigator.of(context).pop(), child: Icon(Icons.close_rounded, color: C.w(0.8))),
        ]),
        const SizedBox(height: 4),
        Text(c.$2, style: TextStyle(fontSize: 13.5, color: C.w(0.55))),
        const SizedBox(height: 16),
        TextField(
          controller: _ctrl,
          autofocus: true,
          enabled: !_busy && _done == null,
          maxLength: c.$3,
          keyboardType: widget.field == _Field.email ? TextInputType.emailAddress : TextInputType.text,
          autocorrect: false,
          style: const TextStyle(fontSize: 16),
          decoration: InputDecoration(
            counterText: '',
            contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
            enabledBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(12), borderSide: BorderSide(color: C.w(0.2))),
            focusedBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(12), borderSide: BorderSide(color: C.w(0.6))),
            disabledBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(12), borderSide: BorderSide(color: C.w(0.1))),
          ),
        ),
        if (_error != null)
          Container(
            margin: const EdgeInsets.only(top: 12),
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
            decoration: BoxDecoration(color: const Color(0x1AEF4444), borderRadius: BorderRadius.circular(12), border: Border.all(color: const Color(0x4DF87171))),
            child: Text(_error!, style: const TextStyle(fontSize: 12.5, color: Color(0xFFFCA5A5))),
          ),
        if (_done != null)
          Container(
            margin: const EdgeInsets.only(top: 12),
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
            decoration: BoxDecoration(color: C.w(0.05), borderRadius: BorderRadius.circular(12), border: Border.all(color: C.w(0.2))),
            child: Text(_done!, style: TextStyle(fontSize: 12.5, color: C.w(0.8))),
          ),
        const SizedBox(height: 16),
        Row(mainAxisAlignment: MainAxisAlignment.end, children: [
          GlassButton(label: _done != null ? 'Close' : 'Cancel', height: 44, onTap: () => Navigator.of(context).pop()),
          if (_done == null) ...[
            const SizedBox(width: 10),
            GlassButton(label: 'Save', kind: GlassKind.white, height: 44, padding: const EdgeInsets.symmetric(horizontal: 28), busy: _busy, onTap: _save),
          ],
        ]),
      ]),
    );
  }
}
