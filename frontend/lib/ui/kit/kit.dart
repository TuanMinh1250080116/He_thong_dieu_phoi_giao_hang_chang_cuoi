import 'package:flutter/material.dart';

import '../../theme/tokens.dart';

class StatusBadge extends StatelessWidget {
  const StatusBadge({super.key, required this.label, this.tone = Tone.neutral, this.icon});

  final String label;
  final Tone tone;
  final IconData? icon;

  @override
  Widget build(BuildContext context) {
    final tokens = AppTokens.of(context);
    final fg = tokens.toneFg[tone]!;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: tokens.toneBg[tone],
        borderRadius: BorderRadius.circular(AppTokens.radius),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (icon != null) ...[
            Icon(icon, size: 14, color: fg),
            const SizedBox(width: 4),
          ],
          Flexible(
            child: Text(
              label,
              style: Theme.of(context).textTheme.labelMedium?.copyWith(color: fg, fontWeight: FontWeight.w600),
            ),
          ),
        ],
      ),
    );
  }
}

class PageBody extends StatelessWidget {
  const PageBody({super.key, required this.children, this.maxWidth = AppTokens.contentMaxWidth, this.onRefresh});

  final List<Widget> children;
  final double maxWidth;
  final Future<void> Function()? onRefresh;

  @override
  Widget build(BuildContext context) {
    final width = MediaQuery.sizeOf(context).width;
    final gutter = width < 600 ? AppTokens.s4 : AppTokens.s5;
    final list = ListView(
      padding: EdgeInsets.fromLTRB(gutter, AppTokens.s4, gutter, AppTokens.s5),
      children: [
        for (final child in children)
          Center(child: ConstrainedBox(constraints: BoxConstraints(maxWidth: maxWidth), child: child)),
      ],
    );
    return onRefresh == null ? list : RefreshIndicator(onRefresh: onRefresh!, child: list);
  }
}

class FormGroup extends StatelessWidget {
  const FormGroup({super.key, required this.title, required this.children, this.trailing});

  final String title;
  final List<Widget> children;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    final tokens = AppTokens.of(context);
    return Padding(
      padding: const EdgeInsets.only(bottom: AppTokens.s4),
      child: Semantics(
        container: true,
        label: title,
        child: Card(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(AppTokens.s4, AppTokens.s3, AppTokens.s4, AppTokens.s2),
                child: Row(
                  children: [
                    Expanded(
                      child: Text(
                        title.toUpperCase(),
                        style: Theme.of(context).textTheme.labelMedium?.copyWith(
                              color: tokens.groupLabel,
                              fontWeight: FontWeight.w700,
                              letterSpacing: 0.8,
                            ),
                      ),
                    ),
                    ?trailing,
                  ],
                ),
              ),
              Divider(color: tokens.line),
              Padding(
                padding: const EdgeInsets.all(AppTokens.s4),
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: children),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class FieldGap extends StatelessWidget {
  const FieldGap({super.key, this.size = AppTokens.s3});

  final double size;

  @override
  Widget build(BuildContext context) => SizedBox(height: size);
}

class InfoRow extends StatelessWidget {
  const InfoRow(this.label, this.value, {super.key, this.valueWidget, this.labelWidth = 132});

  final String label;
  final String? value;
  final Widget? valueWidget;
  final double labelWidth;

  @override
  Widget build(BuildContext context) {
    final narrow = MediaQuery.sizeOf(context).width < 480;
    final labelText = Text(label, style: Theme.of(context).textTheme.bodySmall);
    final content = valueWidget ?? SelectableText(value == null || value!.isEmpty ? '—' : value!);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 5),
      child: narrow
          ? Column(crossAxisAlignment: CrossAxisAlignment.start, children: [labelText, const SizedBox(height: 2), content])
          : Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [SizedBox(width: labelWidth, child: labelText), Expanded(child: content)],
            ),
    );
  }
}

class Callout extends StatelessWidget {
  const Callout(this.text, {super.key, this.tone = Tone.info, this.icon});

  final String text;
  final Tone tone;
  final IconData? icon;

  static const _icons = {
    Tone.neutral: Icons.info_outline,
    Tone.info: Icons.info_outline,
    Tone.ok: Icons.check_circle_outline,
    Tone.warn: Icons.warning_amber_rounded,
    Tone.danger: Icons.error_outline,
  };

  @override
  Widget build(BuildContext context) {
    final tokens = AppTokens.of(context);
    return Container(
      margin: const EdgeInsets.only(bottom: AppTokens.s3),
      padding: const EdgeInsets.all(AppTokens.s3),
      decoration: BoxDecoration(
        color: tokens.toneBg[tone],
        borderRadius: BorderRadius.circular(AppTokens.radius),
        border: Border(left: BorderSide(color: tokens.toneFg[tone]!, width: 3)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon ?? _icons[tone], size: 20, color: tokens.toneFg[tone]),
          const SizedBox(width: AppTokens.s2),
          Expanded(child: Text(text, style: TextStyle(color: tokens.toneFg[tone]))),
        ],
      ),
    );
  }
}

class StatusFooter extends StatelessWidget {
  const StatusFooter(this.text, {super.key});

  final String text;

  @override
  Widget build(BuildContext context) {
    final tokens = AppTokens.of(context);
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: AppTokens.s4, vertical: AppTokens.s2),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surface,
        border: Border(top: BorderSide(color: tokens.line)),
      ),
      child: Text(text, style: Theme.of(context).textTheme.bodySmall),
    );
  }
}

class ActionBar extends StatelessWidget {
  const ActionBar({super.key, required this.children, this.footer});

  final List<Widget> children;
  final String? footer;

  @override
  Widget build(BuildContext context) {
    final tokens = AppTokens.of(context);
    final scheme = Theme.of(context).colorScheme;
    return Material(
      color: scheme.surface,
      child: DecoratedBox(
        decoration: BoxDecoration(border: Border(top: BorderSide(color: tokens.line))),
        child: SafeArea(
          top: false,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: AppTokens.s4, vertical: AppTokens.s3),
                child: Align(
                  alignment: Alignment.centerLeft,
                  child: Wrap(spacing: AppTokens.s2, runSpacing: AppTokens.s2, children: children),
                ),
              ),
              if (footer != null)
                Padding(
                  padding: const EdgeInsets.fromLTRB(AppTokens.s4, 0, AppTokens.s4, AppTokens.s2),
                  child: Align(alignment: Alignment.centerLeft, child: Text(footer!, style: Theme.of(context).textTheme.bodySmall)),
                ),
            ],
          ),
        ),
      ),
    );
  }
}

class FilterPanel extends StatelessWidget {
  const FilterPanel({super.key, required this.children, this.onSearch, this.onClear});

  final List<Widget> children;
  final VoidCallback? onSearch;
  final VoidCallback? onClear;

  @override
  Widget build(BuildContext context) {
    return FormGroup(
      title: 'Bộ lọc',
      children: [
        Wrap(spacing: AppTokens.s3, runSpacing: AppTokens.s3, crossAxisAlignment: WrapCrossAlignment.end, children: [
          for (final child in children) SizedBox(width: 220, child: child),
          if (onSearch != null) FilledButton.icon(onPressed: onSearch, icon: const Icon(Icons.search), label: const Text('Tìm')),
          if (onClear != null) OutlinedButton(onPressed: onClear, child: const Text('Xóa lọc')),
        ]),
      ],
    );
  }
}

class EmptyState extends StatelessWidget {
  const EmptyState({super.key, required this.title, this.message, this.icon = Icons.inbox_outlined, this.action});

  final String title;
  final String? message;
  final IconData icon;
  final Widget? action;

  @override
  Widget build(BuildContext context) {
    final tokens = AppTokens.of(context);
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(AppTokens.s5),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 40, color: tokens.inkSoft),
            const SizedBox(height: AppTokens.s3),
            Text(title, style: Theme.of(context).textTheme.titleMedium, textAlign: TextAlign.center),
            if (message != null) ...[
              const SizedBox(height: AppTokens.s1),
              Text(message!, style: Theme.of(context).textTheme.bodySmall, textAlign: TextAlign.center),
            ],
            if (action != null) ...[const SizedBox(height: AppTokens.s4), action!],
          ],
        ),
      ),
    );
  }
}

class GridColumn<T> {
  const GridColumn({required this.title, required this.cell, this.flex = 1, this.primary = false, this.compactHidden = false});

  final String title;
  final Widget Function(BuildContext context, T row) cell;
  final double flex;
  final bool primary;
  final bool compactHidden;
}

class ResultGrid<T> extends StatelessWidget {
  const ResultGrid({
    super.key,
    required this.columns,
    required this.rows,
    this.onTap,
    this.isSelected,
    this.emptyTitle = 'Không có dữ liệu',
    this.emptyMessage,
    this.emptyIcon = Icons.inbox_outlined,
  });

  final List<GridColumn<T>> columns;
  final List<T> rows;
  final void Function(T row)? onTap;
  final bool Function(T row)? isSelected;
  final String emptyTitle;
  final String? emptyMessage;
  final IconData emptyIcon;

  @override
  Widget build(BuildContext context) {
    if (rows.isEmpty) {
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: AppTokens.s5),
        child: EmptyState(title: emptyTitle, message: emptyMessage, icon: emptyIcon),
      );
    }
    final wide = MediaQuery.sizeOf(context).width >= 720;
    return wide ? _table(context) : _cards(context);
  }

  Widget _table(BuildContext context) {
    final tokens = AppTokens.of(context);
    final scheme = Theme.of(context).colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Container(
          color: tokens.toneBg[Tone.neutral],
          padding: const EdgeInsets.symmetric(horizontal: AppTokens.s3, vertical: AppTokens.s2),
          child: Row(
            children: [
              for (final column in columns)
                Expanded(
                  flex: (column.flex * 10).round(),
                  child: Text(
                    column.title,
                    style: Theme.of(context).textTheme.labelMedium?.copyWith(fontWeight: FontWeight.w700),
                  ),
                ),
            ],
          ),
        ),
        for (final row in rows)
          Material(
            color: (isSelected?.call(row) ?? false) ? scheme.primaryContainer : Colors.transparent,
            child: InkWell(
              onTap: onTap == null ? null : () => onTap!(row),
              child: Container(
                constraints: const BoxConstraints(minHeight: AppTokens.tapTarget),
                padding: const EdgeInsets.symmetric(horizontal: AppTokens.s3, vertical: AppTokens.s2),
                decoration: BoxDecoration(border: Border(bottom: BorderSide(color: tokens.line))),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.center,
                  children: [
                    for (final column in columns)
                      Expanded(flex: (column.flex * 10).round(), child: column.cell(context, row)),
                  ],
                ),
              ),
            ),
          ),
      ],
    );
  }

  Widget _cards(BuildContext context) {
    final tokens = AppTokens.of(context);
    final scheme = Theme.of(context).colorScheme;
    final primary = columns.firstWhere((c) => c.primary, orElse: () => columns.first);
    final others = columns.where((c) => c != primary && !c.compactHidden).toList();
    return Column(
      children: [
        for (final row in rows)
          Padding(
            padding: const EdgeInsets.only(bottom: AppTokens.s2),
            child: Material(
              color: (isSelected?.call(row) ?? false) ? scheme.primaryContainer : scheme.surface,
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(AppTokens.radius),
                side: BorderSide(color: tokens.line),
              ),
              child: InkWell(
                borderRadius: BorderRadius.circular(AppTokens.radius),
                onTap: onTap == null ? null : () => onTap!(row),
                child: Padding(
                  padding: const EdgeInsets.all(AppTokens.s3),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      DefaultTextStyle.merge(
                        style: Theme.of(context).textTheme.titleSmall,
                        child: primary.cell(context, row),
                      ),
                      for (final column in others)
                        Padding(
                          padding: const EdgeInsets.only(top: 6),
                          child: Row(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              SizedBox(
                                width: 104,
                                child: Text(column.title, style: Theme.of(context).textTheme.bodySmall),
                              ),
                              Expanded(child: column.cell(context, row)),
                            ],
                          ),
                        ),
                    ],
                  ),
                ),
              ),
            ),
          ),
      ],
    );
  }
}

class SectionTitle extends StatelessWidget {
  const SectionTitle(this.text, {super.key});

  final String text;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: AppTokens.s2),
      child: Text(text, style: Theme.of(context).textTheme.titleMedium),
    );
  }
}

class WindowFrame extends StatelessWidget {
  const WindowFrame({super.key, required this.title, required this.body, this.actions, this.footer, this.leading});

  final String title;
  final Widget body;
  final ActionBar? actions;
  final String? footer;
  final Widget? leading;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(title), leading: leading),
      body: body,
      bottomNavigationBar: actions ?? (footer == null ? null : StatusFooter(footer!)),
    );
  }
}
